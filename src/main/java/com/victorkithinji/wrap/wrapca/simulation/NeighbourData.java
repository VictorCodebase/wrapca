package com.victorkithinji.wrap.wrapca.simulation;

import lombok.Value;

/**
 * Immutable descriptor for one Moore neighbour returned by MooreNeighbourEvaluator.
 *
 * directionIndex is the direction FROM the queried cell TOWARD this neighbour (0–7,
 * clockwise from N). To get the fire-travel direction (neighbour → target), use
 * (directionIndex + 4) % 8 when calling WindProjectionCalculator.
 */
@Value
public class NeighbourData {
    int    row;
    int    col;
    long   encodedIndex;
    int    directionIndex;
    double distanceMetres;
}