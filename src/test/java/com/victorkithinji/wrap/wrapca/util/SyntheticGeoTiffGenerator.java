package com.victorkithinji.wrap.wrapca.util;

import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridCoverageFactory;
import org.geotools.gce.geotiff.GeoTiffWriter;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;

import javax.media.jai.RasterFactory;
import java.awt.image.DataBuffer;
import java.awt.image.WritableRaster;
import java.io.File;
import java.util.Random;

/**
 * Generates a synthetic GeoTIFF fixture for testing GeoTiffBandReaderService
 * without requiring real CV output.
 * <br/><br/>
 * Produces a 200x200 cell (20km x 20km) raster centred on the Aberdare area
 * with three bands: NDVI, NDMI, ELEVATION — in that order (BandLayout default).
 *<br/><br/>
 * Cell content:
 *   NDVI:       0.65 base (Afromontane forest), with a 40x40 patch of 0.25
 *               (grassland) in the SW quadrant and a 20x20 patch of 0.0
 *               (bare/water) in the NE corner for NON_COMBUSTIBLE testing.
 *   NDMI:       correlated with NDVI (ndvi * 0.7 + noise), range ~0.1–0.55.
 *   ELEVATION:  linear gradient from 1800m (west) to 2200m (east), simulating
 *               the Aberdare escarpment slope.
 * <br/><br/>
 * CRS: EPSG:32737 (WGS 84 / UTM Zone 37S)
 * Origin: approx. Aberdare NP (easting 260000, northing 9862000)
 * <br/><br/>
 * Run once to produce data/geotiff/latest_cv_output.tif for local development.
 * This file is not committed to version control.
 */
public class SyntheticGeoTiffGenerator {

    private static final int ROWS = 200;
    private static final int COLS = 200;
    private static final double CELL_SIZE = 100.0; // metres

    // Aberdare area origin (top-left corner, UTM 37S)
    private static final double ORIGIN_X = 260000.0;
    private static final double ORIGIN_Y = 9862000.0;

    private static final int BANDS = 3; // NDVI=0, NDMI=1, ELEVATION=2

    public static void main(String[] args) throws Exception {
        String outputPath = args.length > 0 ? args[0] : "data/geotiff/latest_cv_output.tif";
        generate(new File(outputPath));
        System.out.println("Synthetic GeoTIFF written to: " + outputPath);
    }

    public static void generate(File outputFile) throws Exception {
        outputFile.getParentFile().mkdirs();

        float[] pixels = buildPixelData();

        WritableRaster raster = RasterFactory.createBandedRaster(
                DataBuffer.TYPE_FLOAT, COLS, ROWS, BANDS, null);

        for (int band = 0; band < BANDS; band++) {
            for (int row = 0; row < ROWS; row++) {
                for (int col = 0; col < COLS; col++) {
                    int idx = band * ROWS * COLS + row * COLS + col;
                    raster.setSample(col, row, band, pixels[idx]);
                }
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

    private static float[] buildPixelData() {
        float[] data = new float[BANDS * ROWS * COLS];
        Random rng = new Random(42); // fixed seed for reproducibility

        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                float ndvi      = computeNdvi(row, col, rng);
                float ndmi      = ndvi * 0.7f + (rng.nextFloat() - 0.5f) * 0.05f;
                float elevation = 1800f + (col / (float) COLS) * 400f; // W→E gradient

                data[0 * ROWS * COLS + row * COLS + col] = ndvi;
                data[1 * ROWS * COLS + row * COLS + col] = Math.max(0f, ndmi);
                data[2 * ROWS * COLS + row * COLS + col] = elevation;
            }
        }
        return data;
    }

    private static float computeNdvi(int row, int col, Random rng) {
        float noise = (rng.nextFloat() - 0.5f) * 0.04f;

        // NE corner: bare/water — NON_COMBUSTIBLE test patch
        if (row < 20 && col >= 180) return 0.01f + noise * 0.1f;

        // SW quadrant: grassland/shrubland patch
        if (row >= 120 && row < 160 && col >= 20 && col < 60) return 0.25f + noise;

        // Everything else: Afromontane forest
        return 0.65f + noise;
    }
}