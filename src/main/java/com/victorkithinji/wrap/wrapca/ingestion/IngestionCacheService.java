package com.victorkithinji.wrap.wrapca.ingestion;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Checks the local cache directory for a GeoTIFF file matching today's date
 * before triggering a re-fetch from the CV module.
 * <p>
 * Cache filename convention: cv_fuel_state_YYYY-MM-DD.tif
 * This convention should be confirmed with the CV team — it is the only
 * coupling point between this service and their output naming.
 */
@Slf4j
@Service
public class IngestionCacheService {

	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
	private static final String CACHE_FILENAME_PREFIX = "cv_fuel_state_";
	private static final String CACHE_FILENAME_SUFFIX = ".tif";

	// ESA and road caches are existence-only — these files are static products
	// that do not change between runs and have no expiry date.
	private static final String ESA_CACHE_FILENAME = "esa_worldcover.tif";
	private static final String ROAD_CACHE_FILENAME = "osm_roads.geojson";
	private static final String FUEL_RISK_CACHE_FILENAME = "fuel_risk_map.tif";

	private final Path cacheDirectory;

	public IngestionCacheService(@Value("${wrap.data.root}") String dataRoot) {
		this.cacheDirectory = Paths.get(dataRoot, "cache");
	}

	/**
	 * Returns the cached GeoTIFF path for today if it exists, otherwise empty.
	 * The caller is responsible for fetching and storing a new file when empty
	 * is returned.
	 */
	public Optional<Path> getCachedFuelState() {
		return getCachedFuelStateForDate(LocalDate.now());
	}

	/**
	 * Package-private overload for testing with a specific date.
	 */
	Optional<Path> getCachedFuelStateForDate(LocalDate date) {
		ensureCacheDirectoryExists();
		Path candidate = cacheDirectory.resolve(buildFilename(date));
		if (Files.exists(candidate)) {
			log.info("Cache hit: using existing fuel state file {}", candidate);
			return Optional.of(candidate);
		}
		log.info("Cache miss for date {}: no file at {}", date, candidate);
		return Optional.empty();
	}

	/**
	 * Stores a downloaded GeoTIFF into the cache directory under today's
	 * date-stamped filename, then returns the stored path.
	 *
	 * @param sourceBytes raw GeoTIFF bytes received from CV module
	 * @return path of the written cache file
	 * @throws IOException if the write fails
	 */
	public Path storeFuelState(byte[] sourceBytes) throws IOException {
		return storeFuelStateForDate(sourceBytes, LocalDate.now());
	}

	/**
	 * Package-private overload for testing with a specific date.
	 */
	Path storeFuelStateForDate(byte[] sourceBytes, LocalDate date) throws IOException {
		ensureCacheDirectoryExists();
		Path target = cacheDirectory.resolve(buildFilename(date));
		Files.write(target, sourceBytes);
		log.info("Stored {} bytes to cache file {}", sourceBytes.length, target);
		return target;
	}

	/**
	 * Returns the expected cache path for today without checking if it exists.
	 * Used by CvApiClient to know where to write a freshly downloaded file.
	 */
	public Path expectedPathForToday() {
		ensureCacheDirectoryExists();
		return cacheDirectory.resolve(buildFilename(LocalDate.now()));
	}

	// ─── ESA WorldCover cache (existence-only, never expires) ───────────────────

	/**
	 * Returns the cached ESA WorldCover GeoTIFF if it exists, otherwise empty.
	 * The ESA file is a static product — once cached it is never re-fetched.
	 */
	public Optional<Path> getCachedEsaLayer() {
		ensureCacheDirectoryExists();
		Path candidate = cacheDirectory.resolve(ESA_CACHE_FILENAME);
		if (Files.exists(candidate)) {
			log.info("ESA cache hit: {}", candidate);
			return Optional.of(candidate);
		}
		log.info("ESA cache miss: no file at {}", candidate);
		return Optional.empty();
	}

	/**
	 * Stores ESA WorldCover GeoTIFF bytes to the cache and returns the written path.
	 *
	 * @param bytes raw GeoTIFF bytes
	 * @return path of the written cache file
	 * @throws IOException if the write fails
	 */
	public Path storeEsaLayer(byte[] bytes) throws IOException {
		ensureCacheDirectoryExists();
		Path target = cacheDirectory.resolve(ESA_CACHE_FILENAME);
		Files.write(target, bytes);
		log.info("Stored ESA WorldCover GeoTIFF: {} bytes → {}", bytes.length, target);
		return target;
	}

	// ─── OSM road cache (existence-only, never expires) ──────────────────────

	/**
	 * Returns the cached OSM road GeoJSON path if it exists, otherwise empty.
	 * Road geometry is a static product — once cached it is never re-fetched.
	 */
	public Optional<Path> getCachedRoadLayer() {
		ensureCacheDirectoryExists();
		Path candidate = cacheDirectory.resolve(ROAD_CACHE_FILENAME);
		if (Files.exists(candidate)) {
			log.info("Road cache hit: {}", candidate);
			return Optional.of(candidate);
		}
		log.info("Road cache miss: no file at {}", candidate);
		return Optional.empty();
	}

	/**
	 * Stores OSM road GeoJSON to the cache and returns the written path.
	 *
	 * @param geojson GeoJSON string of road linestrings
	 * @return path of the written cache file
	 * @throws IOException if the write fails
	 */
	public Path storeRoadLayer(String geojson) throws IOException {
		ensureCacheDirectoryExists();
		Path target = cacheDirectory.resolve(ROAD_CACHE_FILENAME);
		Files.writeString(target, geojson);
		log.info("Stored OSM road layer: {} chars → {}", geojson.length(), target);
		return target;
	}

	// ─── CV fuel risk map cache (existence-only, never expires) ─────────────────

	/**
	 * Returns the cached fuel risk GeoTIFF if it exists, otherwise empty.
	 * The fuel risk file is a static product — once cached it is never re-fetched.
	 */
	public Optional<Path> getCachedFuelRiskLayer() {
		ensureCacheDirectoryExists();
		Path candidate = cacheDirectory.resolve(FUEL_RISK_CACHE_FILENAME);
		if (Files.exists(candidate)) {
			log.info("Fuel risk cache hit: {}", candidate);
			return Optional.of(candidate);
		}
		log.info("Fuel risk cache miss: no file at {}", candidate);
		return Optional.empty();
	}

	/**
	 * Stores the fuel risk GeoTIFF bytes to the cache and returns the written path.
	 *
	 * @param data raw GeoTIFF bytes
	 * @return path of the written cache file
	 * @throws IOException if the write fails
	 */
	public Path storeFuelRiskLayer(byte[] data) throws IOException {
		ensureCacheDirectoryExists();
		Path target = cacheDirectory.resolve(FUEL_RISK_CACHE_FILENAME);
		Files.write(target, data);
		log.info("Stored fuel risk GeoTIFF: {} bytes → {}", data.length, target);
		return target;
	}

	private String buildFilename(LocalDate date) {
		return CACHE_FILENAME_PREFIX + date.format(DATE_FORMAT) + CACHE_FILENAME_SUFFIX;
	}

	private void ensureCacheDirectoryExists() {
		try {
			Files.createDirectories(cacheDirectory);
		} catch (IOException e) {
			throw new IllegalStateException("Cannot create cache directory: " + cacheDirectory, e);
		}
	}
}