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
 * Reads the CV-produced GeoTIFF and extracts NDVI, NDMI, and elevation
 * into parallel float[row][col] arrays for consumption by GridInitialiserService.
 *
 * Band assumptions are isolated in BandLayout — that is the only class
 * that needs changing when the CV contract is confirmed.
 *
 * Windows note: coverage.dispose(true) is called before reader.dispose() to
 * ensure the underlying ImageIO file handle is released in the correct order.
 * Reversing this order leaves the file locked on Windows.
 */
@Slf4j
@Service
public class GeoTiffBandReaderService {

    private final double targetCellSizeMetres;

    public GeoTiffBandReaderService(
            @Value("${wrap.simulation.cell-size-metres}") double targetCellSizeMetres) {
        this.targetCellSizeMetres = targetCellSizeMetres;
    }

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
            // After this call the float arrays are fully on the heap and we no
            // longer need the coverage or its underlying ImageIO stream.
            Raster raster = coverage.getRenderedImage().getData();
            int cols = raster.getWidth();
            int rows = raster.getHeight();

            log.info("Reading GeoTIFF: {}x{} cells from {}", rows, cols, tiffPath);

            float[][] ndvi      = extractBand(raster, rows, cols, BandLayout.NDVI_BAND);
            float[][] ndmi      = extractBand(raster, rows, cols, BandLayout.NDMI_BAND);
            float[][] elevation = extractBand(raster, rows, cols, BandLayout.ELEVATION_BAND);

            ReferencedEnvelope envelope =
                    ReferencedEnvelope.reference(coverage.getEnvelope2D());

            double pixelWidth = envelope.getWidth() / cols;

            if (Math.abs(pixelWidth - targetCellSizeMetres) > 1.0) {
                log.warn("GeoTIFF pixel size {}m differs from target {}m. " +
                        "Grid initialiser will use raster dimensions as-is. " +
                        "Confirm pixel size with CV team.", pixelWidth, targetCellSizeMetres);
            }

            return new GridBands(
                    ndvi, ndmi, elevation,
                    rows, cols,
                    pixelWidth,
                    envelope.getMinX(), envelope.getMinY(),
                    envelope.getMaxX(), envelope.getMaxY()
            );

        } finally {
            // Dispose coverage before reader — this order matters on Windows.
            // Disposing coverage first releases the RenderedImage chain,
            // then reader.dispose() closes the underlying file channel cleanly.
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

    private void validateCoverage(GridCoverage2D coverage, Path tiffPath) {
        int actualBands = coverage.getNumSampleDimensions();
        if (actualBands < BandLayout.EXPECTED_BAND_COUNT) {
            throw new IllegalArgumentException(String.format(
                    "GeoTIFF at %s has %d bands but %d are required (NDVI, NDMI, ELEVATION). " +
                            "Check BandLayout and confirm with CV team.",
                    tiffPath, actualBands, BandLayout.EXPECTED_BAND_COUNT));
        }
        if (actualBands > BandLayout.EXPECTED_BAND_COUNT) {
            log.warn("GeoTIFF has {} bands, expected {}. Extra bands will be ignored.",
                    actualBands, BandLayout.EXPECTED_BAND_COUNT);
        }

        CoordinateReferenceSystem crs = coverage.getCoordinateReferenceSystem2D();
        try {
            CoordinateReferenceSystem expected = CRS.decode(BandLayout.EXPECTED_CRS_CODE);
            if (!CRS.equalsIgnoreMetadata(crs, expected)) {
                log.warn("GeoTIFF CRS does not match expected {}. " +
                                "Grid alignment may be incorrect. Confirm CRS with CV team.",
                        BandLayout.EXPECTED_CRS_CODE);
            }
        } catch (Exception e) {
            log.warn("Could not verify CRS: {}", e.getMessage());
        }
    }
}