package com.victorkithinji.wrap.wrapca.montecarlo;

import lombok.Value;

/**
 * The dual-layer Phase 1 output produced by {@link RiskMapAssembler}.
 *
 * <p>Both arrays are flat, row-major, length {@code rows × cols}.
 * Their index is {@code row * cols + col}.
 */
@Value
public class PhaseOneResult {

    /** Normalised burn frequency per cell: fraction of runs in which the cell burned. Range [0, 1]. */
    float[] damagePotential;

    /**
     * Spatially smoothed ignition likelihood index I(c) per cell.
     * Normalised to [0, 1] across the grid.
     */
    float[] ignitionLikelihood;

    int rows;
    int cols;
}