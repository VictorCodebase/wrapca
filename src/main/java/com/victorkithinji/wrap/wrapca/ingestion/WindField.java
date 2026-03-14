package com.victorkithinji.wrap.wrapca.ingestion;

import lombok.Value;

/**
 * Per-cell wind field aligned to the CA grid.
 * Both arrays are [row][col] with the same dimensions as the CA grid.
 *
 * speedMs:     wind speed in metres per second at 10m height (ERA5 standard)
 * directionDeg: wind direction in meteorological convention — the direction
 *               FROM which the wind blows, measured clockwise from north.
 *               0° = wind from north, 90° = wind from east.
 *               This is the ERA5 convention and must be preserved through
 *               to WindProjectionCalculator without conversion.
 */
@Value
public class WindField {
    float[][] speedMs;
    float[][] directionDeg;
    int rows;
    int cols;

    // Add these to make the calling code work and keep things tidy
    public float getSpeed(int r, int c) {
        return speedMs[r][c];
    }

    public float getDirection(int r, int c) {
        return directionDeg[r][c];
    }
}