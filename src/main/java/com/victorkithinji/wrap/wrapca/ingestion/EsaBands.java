package com.victorkithinji.wrap.wrapca.ingestion;

import lombok.Value;

/**
 * Structured result of reading an ESA WorldCover GeoTIFF.
 *
 * ESA WorldCover is a single-band raster where each pixel carries an integer
 * class code representing land cover type. Class code mappings to VegetationType
 * are defined in EsaBandLayout.
 *
 * Spatial metadata fields follow the same conventions as GridBands:
 * row 0 is the northernmost row, CRS is UTM 37S (EPSG:32737), native pixel
 * size is 10m. Bounding box coordinates are in UTM 37S metres.
 *
 * This object is produced at native resolution by GeoTiffBandReaderService.readEsa()
 * and consumed by RasterResamplerService (Group 5), which downsamples the class
 * codes to the CA target resolution using majority-class resampling.
 */
@Value
public class EsaBands {
    int[][] classCode;
    int rows;
    int cols;
    double cellSizeMetres;
    double minX;
    double minY;
    double maxX;
    double maxY;
}