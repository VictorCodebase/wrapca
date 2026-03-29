package com.victorkithinji.wrap.wrapca.montecarlo;

import com.victorkithinji.wrap.wrapca.grid.CaGrid;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.distribution.EnumeratedIntegerDistribution;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Samples {@code n} ignition seed cells from the grid with probability proportional
 * to the I(c) weight array produced by {@link IgnitionLikelihoodIndexBuilder}.
 *
 * <p>Each sample is an independent draw — the same cell can be selected more than once
 * across the ensemble.  Within a single Monte Carlo task the seed is a single cell, so
 * this class returns a list of {@code n} encoded indices, one per run.
 *
 * <p>Uses Commons Math {@code EnumeratedIntegerDistribution} for weighted sampling.
 * That class normalises the supplied probabilities internally, so the raw I(c) values
 * can be passed directly without prior normalisation.
 *
 * <p>Stateless. Safe to call from multiple threads with different arguments.
 */
@Slf4j
@Service
public class IgnitionSeedSampler {

    /**
     * Draws {@code n} seed cell indices weighted by {@code weights}.
     *
     * @param grid    the CA grid — used only to validate dimensions
     * @param weights flat float[] of I(c) values, row-major, length {@code rows * cols}.
     *                All values must be ≥ 0. At least one must be > 0.
     * @param n       number of seeds to sample (one per Monte Carlo run)
     * @param seed    random seed for reproducibility; pass a different value per
     *                ensemble to get variance across ensembles
     * @return list of {@code n} encoded cell indices ({@code row * cols + col})
     * @throws IllegalArgumentException if {@code weights} length does not match the grid,
     *                                  or if all weights are zero
     */
    public List<Long> sample(CaGrid grid, float[] weights, int n, long seed) {
        int totalCells = grid.rows * grid.cols;

        if (weights.length != totalCells) {
            throw new IllegalArgumentException(
                    "weights length " + weights.length + " does not match grid size " + totalCells);
        }

        // Build parallel int[] singletons and double[] probabilities for EnumeratedIntegerDistribution.
        // Only include cells with positive weight to keep the distribution small.
        int positiveCount = 0;
        for (float w : weights) {
            if (w > 0f) positiveCount++;
        }
        if (positiveCount == 0) {
            throw new IllegalArgumentException(
                    "All I(c) weights are zero — no combustible cells to seed from.");
        }

        int[] singletons    = new int[positiveCount];
        double[] probs      = new double[positiveCount];
        int idx = 0;
        for (int i = 0; i < totalCells; i++) {
            if (weights[i] > 0f) {
                singletons[idx] = i;
                probs[idx]      = weights[i];
                idx++;
            }
        }

        // EnumeratedIntegerDistribution normalises internally
        EnumeratedIntegerDistribution dist =
                new EnumeratedIntegerDistribution(singletons, probs);
        dist.reseedRandomGenerator(seed);

        List<Long> seeds = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            seeds.add((long) dist.sample());
        }

        log.debug("Sampled {} ignition seeds from {} eligible cells (seed={})", n, positiveCount, seed);
        return seeds;
    }
}