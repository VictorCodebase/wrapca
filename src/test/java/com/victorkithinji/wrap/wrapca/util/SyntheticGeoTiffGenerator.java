package com.victorkithinji.wrap.wrapca.util;

import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridCoverageFactory;
import org.geotools.gce.geotiff.GeoTiffWriter;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;

import javax.media.jai.RasterFactory;
import java.awt.image.DataBuffer;
import java.awt.image.WritableRaster;
import java.io.File;
import java.util.Random;

/**
 * Generates a synthetic 11-band GeoTIFF fixture matching the confirmed CV output contract.
 *
 * Band order mirrors the CV contract exactly:
 *   0  Blue, 1 Green, 2 Red, 3 NIR, 4 SWIR,
 *   5  NDVI, 6 NDMI, 7 NDWI,
 *   8  Elevation, 9 Slope, 10 Aspect
 *
 * Resolution: 10m native (matching CV output), producing a 2000x2000 grid
 * for the 20km x 20km Aberdare test area. RasterResamplerService (Group 5)
 * is responsible for downsampling to the CA engine's target resolution.
 *
 * Spatial content:
 *   NDVI:      0.65 base (forest), SW patch 0.25 (grassland), NE patch 0.01 (water)
 *   NDMI:      correlated with NDVI (ndvi * 0.7 + noise)
 *   NDWI:      low values except NE water patch
 *   Elevation: 1800m–2200m W→E gradient (Aberdare escarpment)
 *   Slope:     derived from elevation gradient, range 0–15 degrees
 *   Aspect:    uniform westerly (270°) converted to radians — simplified
 *   Raw bands: plausible Sentinel-2 reflectance values, not used by CA engine
 *
 * CRS: EPSG:32737 (WGS 84 / UTM Zone 37S)
 * Origin: approx. Aberdare NP (easting 260000, northing 9862000)
 *
 * Run once to produce data/geotiff/latest_cv_output.tif for local development.
 * This file is not committed to version control.
 */
public class SyntheticGeoTiffGenerator {

    // 2000x2000 at 10m = 20km x 20km, matching the Aberdare test area
    public static final int ROWS = 2000;
    public static final int COLS = 2000;
    public static final double CELL_SIZE = 10.0; // metres — native CV resolution

    private static final double ORIGIN_X = 260000.0;
    private static final double ORIGIN_Y = 9862000.0;

    private static final int BANDS = 11;

    // Band indices matching BandLayout constants
    private static final int B_BLUE      = 0;
    private static final int B_GREEN     = 1;
    private static final int B_RED       = 2;
    private static final int B_NIR       = 3;
    private static final int B_SWIR      = 4;
    private static final int B_NDVI      = 5;
    private static final int B_NDMI      = 6;
    private static final int B_NDWI      = 7;
    private static final int B_ELEVATION = 8;
    private static final int B_SLOPE     = 9;
    private static final int B_ASPECT    = 10;

    public static void main(String[] args) throws Exception {
        String outputPath = args.length > 0 ? args[0] : "data/geotiff/latest_cv_output.tif";
        generate(new File(outputPath));
        System.out.println("Synthetic GeoTIFF written to: " + outputPath);
    }

    public static void generate(File outputFile) throws Exception {
        outputFile.getParentFile().mkdirs();

        WritableRaster raster = RasterFactory.createBandedRaster(
                DataBuffer.TYPE_FLOAT, COLS, ROWS, BANDS, null);

        Random rng = new Random(42);

        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                float ndvi      = computeNdvi(row, col, rng);
                float ndmi      = Math.max(0f, ndvi * 0.7f + (rng.nextFloat() - 0.5f) * 0.05f);
                float ndwi      = isWaterPatch(row, col) ? 0.4f : -0.2f;
                float elevation = 1800f + (col / (float) COLS) * 400f;
                // Slope derived from the W→E elevation gradient:
                // rise = 400m over 20000m horizontal = 0.02 rad = ~1.15 degrees
                // We add per-cell noise to make it spatially varied and testable
                float slope     = 1.15f + (rng.nextFloat() - 0.5f) * 2.0f;
                slope           = Math.max(0f, slope);
                float aspect    = (float)(Math.PI * 1.5); // 270 degrees in radians (westerly)

                // Plausible Sentinel-2 surface reflectance values (0–1 range)
                float blue  = 0.04f + rng.nextFloat() * 0.02f;
                float green = 0.06f + rng.nextFloat() * 0.02f;
                float red   = isWaterPatch(row, col) ? 0.02f : 0.05f + rng.nextFloat() * 0.03f;
                float nir   = Math.max(0f, ndvi * (red + 0.01f) + red) ; // back-computed from NDVI
                float swir  = Math.max(0f, 1f - ndmi) * 0.3f;

                raster.setSample(col, row, B_BLUE,      blue);
                raster.setSample(col, row, B_GREEN,     green);
                raster.setSample(col, row, B_RED,       red);
                raster.setSample(col, row, B_NIR,       nir);
                raster.setSample(col, row, B_SWIR,      swir);
                raster.setSample(col, row, B_NDVI,      ndvi);
                raster.setSample(col, row, B_NDMI,      ndmi);
                raster.setSample(col, row, B_NDWI,      ndwi);
                raster.setSample(col, row, B_ELEVATION, elevation);
                raster.setSample(col, row, B_SLOPE,     slope);
                raster.setSample(col, row, B_ASPECT,    aspect);
            }
        }

        CoordinateReferenceSystem crs = CRS.decode("EPSG:32737");
        ReferencedEnvelope envelope = new ReferencedEnvelope(
                ORIGIN_X, ORIGIN_X + COLS * CELL_SIZE,
                ORIGIN_Y - ROWS * CELL_SIZE, ORIGIN_Y,
                crs);

        GridCoverageFactory factory = new GridCoverageFactory();
        GridCoverage2D coverage = factory.create("synthetic_fuel_state", raster, envelope);

        GeoTiffWriter writer = new GeoTiffWriter(outputFile);
        writer.write(coverage, null);
        writer.dispose();
    }

    private static float computeNdvi(int row, int col, Random rng) {
        float noise = (rng.nextFloat() - 0.5f) * 0.04f;
        if (isWaterPatch(row, col))     return 0.01f + noise * 0.1f;
        if (isGrasslandPatch(row, col)) return 0.25f + noise;
        return 0.65f + noise;
    }

    // NE corner: bare/water patch (scales with new 2000x2000 dimensions)
    private static boolean isWaterPatch(int row, int col) {
        return row < 200 && col >= 1800;
    }

    // SW quadrant: grassland patch
    private static boolean isGrasslandPatch(int row, int col) {
        return row >= 1200 && row < 1600 && col >= 200 && col < 600;
    }
}