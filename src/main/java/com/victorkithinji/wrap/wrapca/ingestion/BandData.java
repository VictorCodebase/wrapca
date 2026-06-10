package com.victorkithinji.wrap.wrapca.ingestion;

/**
 * Structured output of GeoTiffBandReaderService.
 *
 * All five arrays are aligned to the same rows × cols grid at 100 m resolution.
 * vegetationCode is an integer raster whose values map to VegetationTypeEnum ordinals.
 *
 * Using a Java record keeps this a pure data carrier with no logic — consistent
 * with the Group 4 boundary contract.
 */
public record BandData(
        int      rows,
        int      cols,
        float[][]  ndvi,
        float[][]  ndmi,
        float[][]  slopeRadians,
        float[][]  aspectRadians,
        int[][]    vegetationCode
) {}