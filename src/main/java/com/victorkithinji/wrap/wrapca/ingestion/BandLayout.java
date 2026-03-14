package com.victorkithinji.wrap.wrapca.ingestion;

/**
 * Defines the confirmed band layout of the GeoTIFF produced by the CV module.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * CONFIRMED BY CV TEAM
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Full band order (0-based GeoTools indices):
 *   0  — Blue        (Sentinel-2 B02)
 *   1  — Green       (Sentinel-2 B03)
 *   2  — Red         (Sentinel-2 B04)
 *   3  — NIR         (Sentinel-2 B08)
 *   4  — SWIR        (Sentinel-2 B12)
 *   5  — NDVI        (derived)
 *   6  — NDMI        (derived)
 *   7  — NDWI        (derived)
 *   8  — Elevation   (Copernicus DEM, resampled to 10m)
 *   9  — Slope       (derived from DEM)
 *   10 — Aspect      (derived from DEM)
 *
 * The CA engine only reads bands 5, 6, 8, 9, 10. Bands 0–4 and 7 are present
 * in the file but ignored. They are retained in the count for validation only.
 *
 * CRS: EPSG:32737 (WGS 84 / UTM Zone 37S) — confirmed.
 *
 * Native pixel size: 10m × 10m.
 * All Sentinel-2 bands and DEM layers are resampled to a common 10m grid by CV.
 * The CA engine targets 100m (wrap.simulation.cell-size-metres). Downsampling
 * from 10m to the target resolution is performed by RasterResamplerService
 * (Group 5) — GeoTiffBandReaderService returns native resolution unchanged.
 * ─────────────────────────────────────────────────────────────────────────────
 */
public final class BandLayout {

    private BandLayout() {}

    // Bands read by the CA engine (0-based GeoTools indices)
    public static final int NDVI_BAND      = 5;
    public static final int NDMI_BAND      = 6;
    public static final int ELEVATION_BAND = 8;
    public static final int SLOPE_BAND     = 9;
    public static final int ASPECT_BAND    = 10;

    // Total bands present in the file — used for validation only
    public static final int EXPECTED_BAND_COUNT = 11;

    // Minimum index the reader must be able to reach
    public static final int MAX_REQUIRED_BAND_INDEX = ASPECT_BAND;

    /** Expected CRS authority code. Reader logs a warning if the file differs. */
    public static final String EXPECTED_CRS_CODE = "EPSG:32737";

    /** Expected native pixel size in metres. Reader logs a warning if the file differs. */
    public static final double EXPECTED_NATIVE_PIXEL_SIZE = 10.0;
}