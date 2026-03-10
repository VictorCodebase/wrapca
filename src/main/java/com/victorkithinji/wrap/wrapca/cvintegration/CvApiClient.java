package com.victorkithinji.wrap.wrapca.cvintegration;

import com.victorkithinji.wrap.wrapca.ingestion.IngestionCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * HTTP client to the CV module. Wraps Spring RestClient.
 *
 * Two endpoints consumed:
 *
 *   GET {cv.base-url}/fuel-state
 *     Returns the latest GeoTIFF as application/octet-stream.
 *     Downloaded to the local cache via IngestionCacheService.
 *     Returns Optional.empty() if the CV module is unreachable or has no update.
 *
 *   GET {cv.base-url}/fire-perimeter
 *     Returns a FirePerimeterData JSON payload.
 *     Returns Optional.empty() if no active fire perimeter is available.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * STUB MODE (wrap.cv.stub-mode=true in application.properties):
 * When enabled, both methods return Optional.empty() immediately without
 * making any HTTP calls. This allows Groups 5–12 to be developed and tested
 * without a running CV service. Set stub-mode=false when CV integration begins.
 *
 * Property to add to application.properties for development:
 *   wrap.cv.base-url=http://localhost:5000/api/cv
 *   wrap.cv.stub-mode=true
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Slf4j
@Service
public class CvApiClient {

    private static final String FUEL_STATE_PATH    = "/fuel-state";
    private static final String FIRE_PERIMETER_PATH = "/fire-perimeter";

    private final RestClient restClient;
    private final IngestionCacheService cacheService;
    private final boolean stubMode;

    public CvApiClient(
            @Value("${wrap.cv.base-url:http://localhost:5000/api/cv}") String cvBaseUrl,
            @Value("${wrap.cv.stub-mode:true}") boolean stubMode,
            IngestionCacheService cacheService) {
        this.restClient   = RestClient.builder().baseUrl(cvBaseUrl).build();
        this.cacheService = cacheService;
        this.stubMode     = stubMode;
        if (stubMode) {
            log.info("CvApiClient running in STUB MODE — no HTTP calls will be made to CV.");
        } else {
            log.info("CvApiClient targeting CV module at {}", cvBaseUrl);
        }
    }

    /**
     * Downloads the latest fuel state GeoTIFF from CV and stores it in the local cache.
     *
     * @return path to the cached GeoTIFF, or empty if CV is unavailable or in stub mode
     */
    public Optional<Path> fetchLatestFuelState() {
        if (stubMode) {
            log.debug("Stub mode: skipping fuel state fetch.");
            return Optional.empty();
        }
        try {
            byte[] bytes = restClient.get()
                    .uri(FUEL_STATE_PATH)
                    .retrieve()
                    .body(byte[].class);
            if (bytes == null || bytes.length == 0) {
                log.warn("CV returned empty fuel state response.");
                return Optional.empty();
            }
            Path cached = cacheService.storeFuelState(bytes);
            log.info("Fetched fuel state GeoTIFF from CV: {} bytes → {}", bytes.length, cached);
            return Optional.of(cached);
        } catch (RestClientException e) {
            log.warn("CV fuel-state endpoint unreachable: {}", e.getMessage());
            return Optional.empty();
        } catch (IOException e) {
            log.error("Failed to write CV fuel state to cache", e);
            return Optional.empty();
        }
    }

    /**
     * Fetches the latest fire perimeter observation from CV.
     *
     * @return FirePerimeterData if an active fire perimeter exists, or empty if none
     *         or CV is unavailable or in stub mode
     */
    public Optional<FirePerimeterData> fetchLatestFirePerimeter() {
        if (stubMode) {
            log.debug("Stub mode: skipping fire perimeter fetch.");
            return Optional.empty();
        }
        try {
            FirePerimeterData data = restClient.get()
                    .uri(FIRE_PERIMETER_PATH)
                    .retrieve()
                    .body(FirePerimeterData.class);
            if (data == null) {
                log.info("CV reports no active fire perimeter.");
                return Optional.empty();
            }
            log.info("Fetched fire perimeter from CV: observation time {}",
                    data.getObservationTime());
            return Optional.of(data);
        } catch (RestClientException e) {
            log.warn("CV fire-perimeter endpoint unreachable: {}", e.getMessage());
            return Optional.empty();
        }
    }
}