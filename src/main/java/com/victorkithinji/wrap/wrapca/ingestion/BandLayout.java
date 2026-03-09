package com.victorkithinji.wrap.wrapca.ingestion;

/**
 * Defines the assumed band layout of the GeoTIFF produced by the CV module.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * ASSUMPTION RECORD — confirm with CV team before integration
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Band ordering chosen here: NDVI=1, NDMI=2, ELEVATION=3
 *
 * Rationale for this ordering:
 *   • NDVI and NDMI are both derived from Sentinel-2 reflectance bands and are
 *     the primary CV outputs — placing them first is the natural CV-side ordering.
 *   • Elevation comes from Copernicus DEM, a separate source — placing it last
 *     reflects that it is a secondary, static layer appended to the fuel state map.
 *
 * Alternative orderings to raise with CV:
 *   Option A (chosen here): NDVI=1, NDMI=2, ELEVATION=3
 *     Pro: matches the order CV derives them (optical bands first, DEM appended).
 *     Con: none significant.
 *
 *   Option B: ELEVATION=1, NDVI=2, NDMI=3
 *     Pro: elevation is static and never changes — some pipelines put static
 *          layers first as a convention.
 *     Con: less natural for a CV pipeline that produces optical indices first.
 *
 *   Option C: embed band identity in GeoTIFF band descriptions (metadata)
 *     Pro: completely order-independent; reader resolves by name not index.
 *     Con: requires CV to set band description strings on export, adds a small
 *          GeoTools lookup per read. This is the most robust option and is
 *          worth requesting from CV if their toolchain supports it easily.
 *          GeoTiffBandReaderService supports this path — see USE_BAND_NAMES flag.
 *
 * CRS assumption: WGS 84 / UTM Zone 37S (EPSG:32737)
 *   Appropriate for the Aberdare / central Kenya deployment area.
 *   CV must reproject to this CRS before export, or the grid initialiser
 *   will receive misaligned data. Raise this explicitly in the contract meeting.
 *
 * Pixel size assumption: 100 m
 *   Matches wrap.simulation.cell-size-metres. If CV exports at finer resolution
 *   (e.g. 10 m native Sentinel-2), GeoTiffBandReaderService performs
 *   block-averaging downsampling — see that class for details.
 * ─────────────────────────────────────────────────────────────────────────────
 */
public final class BandLayout {

    private BandLayout() {}

    // GeoTools band indices are 0-based internally but GeoTIFF bands are 1-based.
    // These constants use 0-based indexing as used by GeoTools GridCoverage2D.

    public static final int NDVI_BAND      = 0;
    public static final int NDMI_BAND      = 1;
    public static final int ELEVATION_BAND = 2;

    // Band description strings to use when USE_BAND_NAMES = true.
    // CV must set these in the GeoTIFF band metadata for name-based resolution to work.
    public static final String NDVI_BAND_NAME      = "NDVI";
    public static final String NDMI_BAND_NAME      = "NDMI";
    public static final String ELEVATION_BAND_NAME = "ELEVATION";

    /**
     * When true, GeoTiffBandReaderService resolves bands by description string
     * rather than by index. Safer, but requires CV to set band names on export.
     *
     * Set to false until confirmed with CV team.
     */
    public static final boolean USE_BAND_NAMES = false;

    public static final int EXPECTED_BAND_COUNT = 3;

    /** Expected CRS authority code. Reader will log a warning if the file differs. */
    public static final String EXPECTED_CRS_CODE = "EPSG:32737";
}