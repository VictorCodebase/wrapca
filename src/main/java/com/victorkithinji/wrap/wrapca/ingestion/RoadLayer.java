package com.victorkithinji.wrap.wrapca.ingestion;

import lombok.Value;

import java.util.List;

/**
 * Road and path linestring geometry for the grid area.
 * Produced by OsmRoadLoaderService and consumed by GridInitialiserService (Group 5)
 * for road proximity computation.
 *
 * Each entry in segments is one road or path linestring — an ordered array of
 * [easting, northing] coordinate pairs in UTM 37S metres. The array has shape
 * [n][2] where n is the number of vertices in that linestring.
 *
 * An empty segments list is valid and expected when no road data is available —
 * GridInitialiserService handles this by setting road proximity to Float.MAX_VALUE
 * for all cells, which zeroes out the road proximity term in I(c).
 */
@Value
public class RoadLayer {
    List<double[][]> segments;

    public boolean isEmpty() {
        return segments == null || segments.isEmpty();
    }
}