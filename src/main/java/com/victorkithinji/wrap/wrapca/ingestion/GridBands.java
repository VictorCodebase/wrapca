package com.victorkithinji.wrap.wrapca.ingestion;

import lombok.Value;

/**
 * Structured result of reading a CV GeoTIFF.
 * All three arrays are [row][col] with identical dimensions.
 * Row 0 is the northernmost row (top of the raster).
 */
@Value
public class GridBands {

    float[][] ndvi;
    float[][] ndmi;
    float[][] elevationMetres;
    int rows;
    int cols;

    /** Cell size in metres as read from the GeoTIFF geotransform. */
    double cellSizeMetres;

    /**
     * Bounding box in the GeoTIFF's native CRS (expected: UTM 37S metres).
     * minX = westernmost easting, maxY = northernmost northing.
     */
    double minX;
    double minY;
    double maxX;
    double maxY;
}