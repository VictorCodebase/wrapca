package com.victorkithinji.wrap.wrapca.montecarlo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Converts a completed {@link BurnFrequencyAccumulator} and raw I(c) weights into
 * the normalised dual-layer {@link PhaseOneResult}.
 *
 * <p>Two output layers:
 * <ol>
 *   <li><b>Damage potential</b> — each cell's burn count divided by total runs N.
 *       Directly gives the fraction of simulations in which the cell burned.
 *       Range [0, 1]. No further normalisation beyond dividing by N.</li>
 *   <li><b>Ignition likelihood</b> — the raw I(c) weights normalised so the maximum
 *       value across the grid equals 1.0. This is the "spatially smoothed ignition
 *       likelihood" the proposal describes — smoothing here is achieved by the
 *       sampling distribution itself; no Gaussian kernel is applied at this stage.</li>
 * </ol>
 *
 * <p>Stateless. Safe to call repeatedly.
 */
@Slf4j
@Service
public class RiskMapAssembler {

    /**
     * Assembles the Phase 1 result from accumulator counts and raw I(c) weights.
     *
     * @param accumulator populated after all Monte Carlo tasks have completed
     * @param rawIc       flat float[] of I(c) weights from
     *                    {@link IgnitionLikelihoodIndexBuilder#build}, row-major,
     *                    length {@code rows × cols}
     * @param totalRuns   number of Monte Carlo runs ({@code seeds.size()})
     * @return assembled dual-layer result
     */
    public PhaseOneResult assemble(BurnFrequencyAccumulator accumulator,
                                   float[] rawIc,
                                   int totalRuns) {

        int rows = accumulator.getRows();
        int cols = accumulator.getCols();
        int size = rows * cols;

        if (rawIc.length != size) {
            throw new IllegalArgumentException(
                    "rawIc length " + rawIc.length + " does not match accumulator size " + size);
        }
        if (totalRuns <= 0) {
            throw new IllegalArgumentException("totalRuns must be > 0, got " + totalRuns);
        }

        // --- damage potential: burn frequency / N ---
        float[] damagePotential = new float[size];
        int[][] counts = accumulator.snapshot();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                damagePotential[r * cols + c] = (float) counts[r][c] / totalRuns;
            }
        }

        // --- ignition likelihood: normalise I(c) to [0, 1] by dividing by max ---
        float maxIc = 0f;
        for (float v : rawIc) {
            if (v > maxIc) maxIc = v;
        }

        float[] ignitionLikelihood = new float[size];
        if (maxIc > 0f) {
            for (int i = 0; i < size; i++) {
                ignitionLikelihood[i] = rawIc[i] / maxIc;
            }
        }
        // else: all zeros — grid was entirely non-combustible; leave as zero array

        log.info("Phase 1 assembled: {} runs, peak burn freq = {:.3f}, peak I(c) = {:.3f}",
                totalRuns,
                maxOf(damagePotential),
                maxOf(ignitionLikelihood));

        return new PhaseOneResult(damagePotential, ignitionLikelihood, rows, cols);
    }

    // -------------------------------------------------------------------------

    private static float maxOf(float[] arr) {
        float m = 0f;
        for (float v : arr) if (v > m) m = v;
        return m;
    }
}