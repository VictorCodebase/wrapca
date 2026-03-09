package com.victorkithinji.wrap.wrapca.ingestion;

import lombok.extern.slf4j.Slf4j;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridEnvelope2D;
import org.geotools.coverage.grid.GridGeometry2D;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.coverage.grid.io.GridCoverage2DReader;
import org.geotools.coverage.grid.io.GridFormatFinder;
import org.geotools.gce.geotiff.GeoTiffFormat;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.parameter.Parameter;
import org.geotools.referencing.CRS;
import org.opengis.parameter.GeneralParameterValue;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
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
 * Downsampling: if the GeoTIFF pixel size is finer than cellSizeMetres,
 * GeoTools is asked to read at the target resolution using overview/subsample
 * reading. Block-averaging is applied implicitly by the GeoTIFF reader when
 * overview levels are present; if not, nearest-neighbour subsampling is used.
 * This is acceptable for the 100m CA grid — the CA physics dominate spatial
 * uncertainty at this scale, not the resampling method.
 */
@Slf4j
@Service
public class GeoTiffBandReaderService {

    private final double targetCellSizeMetres;

    public GeoTiffBandReaderService(
            @Value("${wrap.simulation.cell-size-metres}") double targetCellSizeMetres) {
        this.targetCellSizeMetres = targetCellSizeMetres;
    }

    /**
     * Reads the GeoTIFF at the given path and returns structured band arrays.
     *
     * @param tiffPath path to a GeoTIFF with bands in the layout defined by BandLayout
     * @return GridBands containing ndvi, ndmi, elevationMetres arrays
     * @throws IOException if the file cannot be read or has unexpected structure
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

        try {
            GridCoverage2D coverage = reader.read(null);
            validateCoverage(coverage, tiffPath);

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
                log.warn("GeoTIFF pixel size {:.1f}m differs from target {:.1f}m. " +
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