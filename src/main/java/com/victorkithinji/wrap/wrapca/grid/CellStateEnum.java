package com.victorkithinji.wrap.wrapca.grid;

/**
 * The four discrete states a cell in the CA grid can occupy.
 *
 * <p>State transitions follow a strict one-way progression:
 * {@code UNBURNED → BURNING → BURNED}.
 * {@code NON_COMBUSTIBLE} cells never transition and are excluded from all
 * spread calculations.
 *
 * <p>Ordinal values are used as indices into {@code int[][]} state arrays
 * inside {@link CaGrid} for performance — do not reorder these constants.
 */
public enum CellStateEnum {

    /**
     * Vegetated cell that has not yet ignited. Eligible to receive fire from
     * BURNING neighbours each generation.
     */
    UNBURNED,

    /**
     * Cell currently on fire. Evaluated as a fire source when computing
     * ignition probability for its UNBURNED Moore neighbours.
     * Transitions to {@code BURNED} after one generation (single-step burn
     * duration model; extend here if a multi-step residence time is needed).
     */
    BURNING,

    /**
     * Cell that has completed combustion. No longer contributes to spread
     * and cannot re-ignite.
     */
    BURNED,

    /**
     * Permanently non-combustible cell: open water, bare rock, built-up area.
     * Assigned at grid initialisation from the CV fuel-state layer and OSM
     * land-cover data. Never participates in transition calculations.
     */
    NON_COMBUSTIBLE
}