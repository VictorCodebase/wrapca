package com.victorkithinji.wrap.wrapca.grid;

import lombok.Value;

/**
 * Return type of {@link GridInitialiserService#build}.
 *
 * <p>Bundles the initialised {@link CaGrid} with the road-proximity array
 * that {@code IgnitionLikelihoodIndexBuilder} (Group 7) needs for I(c).
 * Both are produced in the same cell loop, so they travel together.
 */
@Value
public class GridInitResult {

    /**
     * The fully initialised CA grid. Cell states are either
     * {@link CellStateEnum#UNBURNED} or {@link CellStateEnum#NON_COMBUSTIBLE}.
     */
    CaGrid grid;

    /**
     * Minimum distance in metres from each cell centre to the nearest point
     * on any road segment, indexed {@code [row][col]}.
     *
     * <p>A value of {@link Float#MAX_VALUE} indicates no road segments were
     * present in the loaded {@link com.victorkithinji.wrap.wrapca.ingestion.RoadLayer}
     * (e.g. the file was missing). Group 7 must treat MAX_VALUE as "no road
     * influence" and zero out the road term in I(c) accordingly.
     */
    float[][] roadProximityMetres;
}