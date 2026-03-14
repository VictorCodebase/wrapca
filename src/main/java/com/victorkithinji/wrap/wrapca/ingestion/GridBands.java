package com.victorkithinji.wrap.wrapca.ingestion;

import lombok.Value;

/**
 * Structured result of reading a CV GeoTIFF.
 * All five arrays are [row][col] with identical dimensions.
 * Row 0 is the northernmost row (top of the raster).
 *
 * When returned by GeoTiffBandReaderService, dimensions reflect the native
 * pixel size (10m from CV — rows and cols will be large, e.g. 2000x2000
 * for a 20km x 20km area). RasterResamplerService (Group 5) returns a new
 * GridBands instance at the target CA resolution with updated rows, cols,
 * and cellSizeMetres. GridInitialiserService operates on the resampled instance.
 *
 * Slope and aspect are provided directly by CV — they do not need to be
 * derived from elevation by the CA engine.
 */
@Value
public class GridBands {

    float[][] ndvi;
    float[][] ndmi;
    float[][] elevationMetres;

    /** Terrain slope in degrees as provided by CV (derived from Copernicus DEM). */
    float[][] slopeDegrees;

    /**
     * Terrain aspect in radians as provided by CV (derived from Copernicus DEM).
     * Convention: clockwise from north, range [0, 2π].
     */
    float[][] aspectRadians;

    int rows;
    int cols;

    /** Cell size in metres — native resolution when from GeoTiffBandReaderService,
     *  target resolution when from RasterResamplerService. */
    double cellSizeMetres;

    /**
     * Bounding box in the GeoTIFF's native CRS (UTM 37S metres).
     * minX = westernmost easting, maxY = northernmost northing.
     * Preserved unchanged through resampling.
     */
    double minX;
    double minY;
    double maxX;
    double maxY;
}