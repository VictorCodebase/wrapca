package com.victorkithinji.wrap.wrapca.ingestion;

import lombok.extern.slf4j.Slf4j;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.io.GridCoverage2DReader;
import org.geotools.gce.geotiff.GeoTiffFormat;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.awt.image.Raster;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Reads the CV GeoTIFF and extracts the five bands the CA engine needs:
 * NDVI, NDMI, elevation, slope, and aspect.
 *
 * Returns a GridBands at the file's native resolution (10m from CV).
 * Downsampling to the CA engine's target resolution is the responsibility
 * of RasterResamplerService (Group 5) — this class does not resample.
 *
 * Band indices are defined in BandLayout. That is the only file to update
 * if CV changes its band layout in future.
 *
 * Windows note: coverage.dispose(true) is called before reader.dispose() to
 * release the underlying ImageIO file handle in the correct order.
 * Reversing this order leaves the file locked on Windows.
 */
@Slf4j
@Service
public class GeoTiffBandReaderService {

    public GeoTiffBandReaderService(
            @Value("${wrap.simulation.cell-size-metres}") double ignoredTargetSize) {
        // Target cell size is no longer used here — resampling is Group 5's concern.
        // The parameter is retained to avoid breaking Spring's dependency injection
        // if SimulationConfig injects it, and serves as an explicit reminder that
        // this service intentionally does not resample.
    }

    /**
     * Reads the GeoTIFF at the given path and returns native-resolution band arrays.
     *
     * @param tiffPath path to the CV GeoTIFF
     * @return GridBands at native resolution (rows/cols reflect the file's pixel size)
     * @throws IOException if the file does not exist or is not a valid GeoTIFF
     */
    public GridBands read(Path tiffPath) throws IOException {
        File file = tiffPath.toFile();
        if (!file.exists()) {
            throw new IOException("GeoTIFF not found: " + tiffPath);
        }

        GeoTiffFormat format = new GeoTiffFormat();
        GridCoverage2DReader reader = format.getReader(file);
        if (reader == null) {
            throw new IOException("File is not a valid GeoTIFF: " + tiffPath);
        }

        GridCoverage2D coverage = null;
        try {
            coverage = reader.read(null);
            validateCoverage(coverage, tiffPath);

            // getData() copies all pixel values into a detached in-memory Raster.
            // After this call the float arrays are fully on the heap and the
            // coverage and its ImageIO stream are no longer needed.
            Raster raster = coverage.getRenderedImage().getData();
            int cols = raster.getWidth();
            int rows = raster.getHeight();

            log.info("Reading GeoTIFF: {}x{} cells at native resolution from {}",
                    rows, cols, tiffPath);

            float[][] ndvi      = extractBand(raster, rows, cols, BandLayout.NDVI_BAND);
            float[][] ndmi      = extractBand(raster, rows, cols, BandLayout.NDMI_BAND);
            float[][] elevation = extractBand(raster, rows, cols, BandLayout.ELEVATION_BAND);
            float[][] slope     = extractBand(raster, rows, cols, BandLayout.SLOPE_BAND);
            float[][] aspect    = extractBand(raster, rows, cols, BandLayout.ASPECT_BAND);

            ReferencedEnvelope envelope =
                    ReferencedEnvelope.reference(coverage.getEnvelope2D());

            double pixelWidth = envelope.getWidth() / cols;

            if (Math.abs(pixelWidth - BandLayout.EXPECTED_NATIVE_PIXEL_SIZE) > 0.5) {
                log.warn("GeoTIFF native pixel size {}m differs from expected {}m. " +
                        "RasterResamplerService will still resample to the configured " +
                        "target resolution.", pixelWidth, BandLayout.EXPECTED_NATIVE_PIXEL_SIZE);
            }

            return new GridBands(
                    ndvi, ndmi, elevation, slope, aspect,
                    rows, cols,
                    pixelWidth,
                    envelope.getMinX(), envelope.getMinY(),
                    envelope.getMaxX(), envelope.getMaxY()
            );

        } finally {
            if (coverage != null) {
                coverage.dispose(true);
            }
            reader.dispose();
        }
    }


    /**
     * Reads an ESA WorldCover GeoTIFF and returns a native-resolution EsaBands object.
     *
     * ESA WorldCover is a single-band raster of integer class codes. The reader
     * extracts band 0 and stores values as int[][]. All other conventions (CRS,
     * pixel size, row ordering) match the CV GeoTIFF reader above.
     *
     * @param esaTiffPath path to the ESA WorldCover GeoTIFF
     * @return EsaBands at native resolution (10m)
     * @throws IOException if the file does not exist or is not a valid GeoTIFF
     */
    public EsaBands readEsa(Path esaTiffPath) throws IOException {
        File file = esaTiffPath.toFile();
        if (!file.exists()) {
            throw new IOException("ESA WorldCover GeoTIFF not found: " + esaTiffPath);
        }

        GeoTiffFormat format = new GeoTiffFormat();
        GridCoverage2DReader reader = format.getReader(file);
        if (reader == null) {
            throw new IOException("File is not a valid GeoTIFF: " + esaTiffPath);
        }

        GridCoverage2D coverage = null;
        try {
            coverage = reader.read(null);

            if (coverage.getNumSampleDimensions() < 1) {
                throw new IllegalArgumentException(
                        "ESA GeoTIFF at " + esaTiffPath + " has no bands.");
            }

            Raster raster = coverage.getRenderedImage().getData();
            int cols = raster.getWidth();
            int rows = raster.getHeight();

            log.info("Reading ESA WorldCover GeoTIFF: {}x{} cells from {}", rows, cols, esaTiffPath);

            int[][] classCodes = extractIntBand(raster, rows, cols);

            ReferencedEnvelope envelope =
                    ReferencedEnvelope.reference(coverage.getEnvelope2D());
            double pixelWidth = envelope.getWidth() / cols;

            return new EsaBands(
                    classCodes, rows, cols, pixelWidth,
                    envelope.getMinX(), envelope.getMinY(),
                    envelope.getMaxX(), envelope.getMaxY()
            );

        } finally {
            if (coverage != null) {
                coverage.dispose(true);
            }
            reader.dispose();
        }
    }

    private float[][] extractBand(Raster raster, int rows, int cols, int bandIndex) {
        float[][] result = new float[rows][cols];
        float[] pixel = new float[raster.getNumBands()];
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                raster.getPixel(
                        raster.getMinX() + col,
                        raster.getMinY() + row,
                        pixel);
                result[row][col] = pixel[bandIndex];
            }
        }
        return result;
    }


    private int[][] extractIntBand(Raster raster, int rows, int cols) {
        int[][] result = new int[rows][cols];
        int[] pixel = new int[raster.getNumBands()];
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                raster.getPixel(
                        raster.getMinX() + col,
                        raster.getMinY() + row,
                        pixel);
                result[row][col] = pixel[0];
            }
        }
        return result;
    }

    private void validateCoverage(GridCoverage2D coverage, Path tiffPath) {
        int actualBands = coverage.getNumSampleDimensions();
        if (actualBands <= BandLayout.MAX_REQUIRED_BAND_INDEX) {
            throw new IllegalArgumentException(String.format(
                    "GeoTIFF at %s has %d bands but band index %d (ASPECT) is required. " +
                            "Expected %d bands total. Check BandLayout against CV output.",
                    tiffPath, actualBands,
                    BandLayout.MAX_REQUIRED_BAND_INDEX, BandLayout.EXPECTED_BAND_COUNT));
        }
        if (actualBands != BandLayout.EXPECTED_BAND_COUNT) {
            log.warn("GeoTIFF has {} bands, expected {}. Proceeding — required bands are present.",
                    actualBands, BandLayout.EXPECTED_BAND_COUNT);
        }

        CoordinateReferenceSystem crs = coverage.getCoordinateReferenceSystem2D();
        try {
            CoordinateReferenceSystem expected = CRS.decode(BandLayout.EXPECTED_CRS_CODE);
            if (!CRS.equalsIgnoreMetadata(crs, expected)) {
                log.warn("GeoTIFF CRS does not match expected {}. " +
                        "Grid alignment may be incorrect.", BandLayout.EXPECTED_CRS_CODE);
            }
        } catch (Exception e) {
            log.warn("Could not verify CRS: {}", e.getMessage());
        }
    }
}