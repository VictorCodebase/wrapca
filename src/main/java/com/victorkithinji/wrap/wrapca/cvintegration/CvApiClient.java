package com.victorkithinji.wrap.wrapca.cvintegration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.victorkithinji.wrap.wrapca.ingestion.IngestionCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * HTTP boundary between the CA engine and the CV module.
 * <p>
 * Resolution order for fetchLatestFuelState():
 * 1. Today's cache file — if present, return immediately (no HTTP call)
 * 2. CV HTTP endpoint — if reachable, download, cache, and return
 * 3. Latest file in cache — if CV is unreachable, fall back to most recent
 * cached file regardless of date (requirement 3)
 * 4. Local override file at wrap.cv.local-geotiff-path — if set and exists,
 * copy into cache and return (requirement 2 — manual test data)
 * 5. Optional.empty() — nothing available anywhere; logged at ERROR
 * <p>
 * stub-mode=true skips steps 1-2 (no HTTP) but still executes steps 3-4,
 * so local files and cached files are always honoured in development.
 */
@Slf4j
@Service
public class CvApiClient {

	private static final String FUEL_STATE_ENDPOINT = "/fuel-state";
	private static final String FIRE_PERIMETER_ENDPOINT = "/fire-perimeter";

	@Value("${wrap.cv.base-url}")
	private String baseUrl;

	@Value("${wrap.cv.stub-mode:true}")
	private boolean stubMode;

	/**
	 * Optional path to a manually placed local GeoTIFF for development/testing.
	 * When set and the file exists, it is treated as a cached fuel state and
	 * used as the final fallback. Set via wrap.cv.local-geotiff-path in
	 * application.properties. Leave unset (or point to a non-existent file)
	 * to disable.
	 */
	@Value("${wrap.cv.local-geotiff-path:}")
	private String localGeotiffPath;

	private final IngestionCacheService cacheService;
	private final ObjectMapper objectMapper;
	private final RestClient restClient;

	public CvApiClient(IngestionCacheService cacheService, ObjectMapper objectMapper) {
		this.cacheService = cacheService;
		this.objectMapper = objectMapper;
		this.restClient = RestClient.create();
	}

	// -------------------------------------------------------------------------
	// fetchLatestFuelState
	// -------------------------------------------------------------------------

	/**
	 * Returns a path to the best available CV fuel-state GeoTIFF.
	 * Never throws. Always logs clearly when a source is tried and fails.
	 */
	public Optional<Path> fetchLatestFuelState() {

		// Step 1: today's cache — fastest path, no I/O beyond a file-exists check
		Optional<Path> today = cacheService.getCachedFuelState();
		if (today.isPresent()) {
			log.info("CvApiClient: using today's cached fuel state — {}",
				today.get());
			return today;
		}

		// Step 2: live CV endpoint (skipped in stub mode)
		if (!stubMode) {
			Optional<Path> downloaded = downloadFuelState();
			if (downloaded.isPresent()) {
				return downloaded;
			}
		} else {
			log.debug("CvApiClient: stub-mode=true — skipping CV HTTP call for fuel state");
		}

		// Step 3: fall back to the most recent file already in cache,
		// regardless of date — covers CV downtime and development restarts
		Optional<Path> latest = cacheService.getLatestCachedFuelState();
		if (latest.isPresent()) {
			log.warn("CvApiClient: CV unavailable — falling back to latest cached " +
				"fuel state: {}", latest.get());
			return latest;
		}

		// Step 4: manually placed local file (development / test data)
		if (localGeotiffPath != null && !localGeotiffPath.isBlank()) {
			Path local = Path.of(localGeotiffPath);
			if (Files.exists(local)) {
				log.info("CvApiClient: using local override GeoTIFF — {}", local);
				try {
					byte[] bytes = Files.readAllBytes(local);
					Path cached = cacheService.storeFuelState(bytes);
					log.info("CvApiClient: local GeoTIFF copied into cache — {}", cached);
					return Optional.of(cached);
				} catch (Exception e) {
					log.error("CvApiClient: failed to copy local GeoTIFF into cache " +
						"(path={}) — {}", local, e.getMessage(), e);
				}
			} else {
				log.warn("CvApiClient: wrap.cv.local-geotiff-path is set but file " +
					"does not exist — {}", local.toAbsolutePath());
			}
		}

		// Step 5: nothing available
		log.error("CvApiClient: no fuel-state GeoTIFF available from any source. " +
				"Checked: today's cache ({}), CV endpoint ({}), historical cache, " +
				"local override ({}). Grid will not be initialised.",
			cacheService.expectedPathForToday(),
			stubMode ? "skipped — stub-mode=true" : baseUrl + FUEL_STATE_ENDPOINT,
			localGeotiffPath == null || localGeotiffPath.isBlank()
				? "not configured" : localGeotiffPath);
		return Optional.empty();
	}

	// -------------------------------------------------------------------------
	// fetchLatestFirePerimeter
	// -------------------------------------------------------------------------

	/**
	 * Fetches the current fire perimeter from CV.
	 * Returns Optional.empty() when CV is unreachable or reports no active fire.
	 * In stub mode returns empty immediately (no HTTP call).
	 */
	public Optional<FirePerimeterData> fetchLatestFirePerimeter() {
		if (stubMode) {
			log.debug("CvApiClient: stub-mode=true — returning empty fire perimeter");
			return Optional.empty();
		}

		String url = baseUrl + FIRE_PERIMETER_ENDPOINT;
		try {
			String json = restClient.get()
				.uri(url)
				.retrieve()
				.body(String.class);

			if (json == null || json.isBlank()) {
				log.info("CvApiClient: CV fire-perimeter endpoint returned empty " +
					"response — no active fire ({})", url);
				return Optional.empty();
			}

			FirePerimeterData data = objectMapper.readValue(json, FirePerimeterData.class);
			log.info("CvApiClient: received fire perimeter from CV " +
				"(observationTime={})", data.getObservationTime());
			return Optional.of(data);

		} catch (RestClientException e) {
			log.warn("CvApiClient: CV fire-perimeter endpoint unreachable " +
				"(url={}) — treating as no active fire: {}", url, e.getMessage());
			return Optional.empty();
		} catch (Exception e) {
			log.warn("CvApiClient: failed to parse fire perimeter response " +
				"(url={}) — {}", url, e.getMessage(), e);
			return Optional.empty();
		}
	}

	// -------------------------------------------------------------------------
	// Internal
	// -------------------------------------------------------------------------

	private Optional<Path> downloadFuelState() {
		String url = baseUrl + FUEL_STATE_ENDPOINT;
		try {
			log.info("CvApiClient: downloading fuel-state GeoTIFF from CV — {}", url);
			byte[] bytes = restClient.get()
				.uri(url)
				.retrieve()
				.body(byte[].class);

			if (bytes == null || bytes.length == 0) {
				log.warn("CvApiClient: CV fuel-state endpoint returned empty body ({})", url);
				return Optional.empty();
			}

			Path cached = cacheService.storeFuelState(bytes);
			log.info("CvApiClient: fuel-state GeoTIFF downloaded and cached — {} ({} KB)",
				cached, bytes.length / 1024);
			return Optional.of(cached);

		} catch (RestClientException e) {
			log.warn("CvApiClient: CV fuel-state endpoint unreachable " +
				"(url={}) — will try cache fallback: {}", url, e.getMessage());
			return Optional.empty();
		} catch (Exception e) {
			log.warn("CvApiClient: failed to download or cache fuel-state GeoTIFF " +
				"(url={}) — {}", url, e.getMessage(), e);
			return Optional.empty();
		}
	}
}