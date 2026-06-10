package com.victorkithinji.wrap.wrapca.ingestion;

/**
 * Defines the confirmed band layout of the GeoTIFF produced by the CV module.
 * Full band order (0-based GeoTools indices):
 * 0  — Blue        (Sentinel-2 B02)
 * 1  — Green       (Sentinel-2 B03)
 * 2  — Red         (Sentinel-2 B04)
 * 3  — NIR         (Sentinel-2 B08)
 * 4  — SWIR        (Sentinel-2 B12)
 * 5  — NDVI        (derived)
 * 6  — NDMI        (derived)
 * 7  — NDWI        (derived)
 * 8  — FMC         (Fuel Moisture Content — direct measurement, replaces NDMI proxy)
 * 9  — Elevation   (Copernicus DEM)
 * 10 — Slope       (derived from DEM)
 * 11 — Aspect      (derived from DEM)
 * <p>
 * The CA engine reads bands 5, 6, 8, 9, 10, 11. Bands 0–4 and 7 are present
 * in the file but ignored. They are retained in the count for validation only.
 * <p>
 * CRS: EPSG:4326 (WGS 84 geographic, degrees).
 * GeoTiffBandReaderService reprojects the bounding box to EPSG:32737
 * (WGS 84 / UTM Zone 37S, metres) before returning GridBands, so all
 * downstream consumers receive spatial metadata in UTM metres and are
 * completely unaware that the source CRS is geographic.
 * <p>
 * Native pixel size: 10m × 10m (Sentinel-2 native resolution).
 * Pixel size is derived post-reprojection in metres.
 * ─────────────────────────────────────────────────────────────────────────────
 */
public final class BandLayout {

	private BandLayout() {
	}

	// Bands read by the CA engine (0-based GeoTools indices)
	public static final int NDVI_BAND = 5;
	public static final int NDMI_BAND = 6;
	public static final int FMC_BAND = 8;
	public static final int ELEVATION_BAND = 9;
	public static final int SLOPE_BAND = 10;
	public static final int ASPECT_BAND = 11;

	// Total bands present in the file — used for validation only
	public static final int EXPECTED_BAND_COUNT = 12;

	// Minimum index the reader must be able to reach
	public static final int MAX_REQUIRED_BAND_INDEX = ASPECT_BAND;

	/**
	 * Source CRS of the GeoTIFF as delivered by CV.
	 */
	public static final String SOURCE_CRS_CODE = "EPSG:4326";

	/**
	 * Target CRS for all spatial metadata returned to downstream consumers.
	 * GeoTiffBandReaderService reprojects the bounding box to this CRS.
	 * Pixel arrays are not reprojected — only the envelope coordinates.
	 */
	public static final String TARGET_CRS_CODE = "EPSG:32737";

	/**
	 * Expected native pixel size in metres after reprojection.
	 */
	public static final double EXPECTED_NATIVE_PIXEL_SIZE = 10.0;
}