package com.victorkithinji.wrap.wrapca.montecarlo;

import com.victorkithinji.wrap.wrapca.grid.CaGrid;
import com.victorkithinji.wrap.wrapca.grid.CellStateEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Computes the ignition likelihood index I(c) for every cell in the grid.
 *
 * <p>I(c) is a weighted combination of three observable variables:
 * <ol>
 *   <li>Vegetation dryness — derived from NDMI (lower NDMI = drier = higher weight)</li>
 *   <li>Historical fire occurrence density — from NASA FIRMS archive (DEV-006: stubbed to
 *       uniform zero; plug in a real FirmsLayer when ingestion is ready)</li>
 *   <li>Human activity proximity — road proximity in metres from GridInitResult
 *       (closer road = higher weight, per DEV-005)</li>
 * </ol>
 *
 * <p>The output is a flat {@code float[]} probability-weight array, one value per cell,
 * in row-major order ({@code index = row * cols + col}).  Values are non-negative
 * and sum to > 0 across combustible cells so that {@link IgnitionSeedSampler} can use
 * them directly as a sampling distribution.
 *
 * <p>NON_COMBUSTIBLE cells always receive weight 0 — they can never be seeded.
 *
 * <p>Stateless. Safe to call repeatedly with different grids.
 */
@Slf4j
@Service
public class IgnitionLikelihoodIndexBuilder {

    // --- term weights (sum to 1.0) ---
    /** Weight applied to the dryness term. */
    private static final float W_DRYNESS   = 0.5f;
    /** Weight applied to the FIRMS historical fire density term. */
    private static final float W_FIRMS     = 0.2f;
    /** Weight applied to the road proximity term. */
    private static final float W_ROAD      = 0.3f;

    /**
     * Builds the I(c) weight array for the given grid.
     *
     * @param grid             the initialised CA grid (states and environments intact)
     * @param roadProximityMetres per-cell minimum distance to nearest road vertex,
     *                         {@code [row][col]}.  {@code Float.MAX_VALUE} signals no
     *                         roads loaded — the road term is zeroed out in that case.
     * @return flat float[] of length {@code grid.rows * grid.cols}, row-major.
     *         All values ≥ 0. NON_COMBUSTIBLE cells are exactly 0.
     */
    public float[] build(CaGrid grid, float[][] roadProximityMetres) {

        int rows = grid.rows;
        int cols = grid.cols;
        float[] raw = new float[rows * cols];

        // --- detect whether road data is actually present ---
        // If every reachable cell has Float.MAX_VALUE, roads were not loaded.
        // We check the [0][0] cell as a fast proxy; GridInitialiserService guarantees
        // uniform MAX_VALUE when RoadLayer was empty.
        boolean roadsAbsent = (roadProximityMetres[0][0] == Float.MAX_VALUE);
        if (roadsAbsent) {
            log.warn("Road proximity data absent (all MAX_VALUE). Road term in I(c) will be zero.");
        }

        // --- pass 1: compute raw scores ---
        float maxRoad = 0f;
        if (!roadsAbsent) {
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    float d = roadProximityMetres[r][c];
                    if (d > maxRoad) maxRoad = d;
                }
            }
        }

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int idx = r * cols + c;

                // NON_COMBUSTIBLE cells never receive weight
                if (grid.getState(r, c) == CellStateEnum.NON_COMBUSTIBLE) {
                    raw[idx] = 0f;
                    continue;
                }

                float ndmi = grid.environment[r][c].getNdmi();

                // 1. Dryness term: low NDMI → high score.
                //    NDMI is already scaled to ~[0.03, 0.40] by GridInitialiserService.
                //    Invert: drier cells score higher.
                float drynessScore = 1.0f - clamp01(ndmi / 0.40f);

                // 2. FIRMS term — DEV-006: no ingestion service for FIRMS yet.
                //    Uniform zero preserves the interface; plug in real density here.
                float firmsScore = 0f;

                // 3. Road proximity term: closer to roads → higher human-activity risk.
                //    Normalise by the grid-wide maximum distance, then invert.
                float roadScore = 0f;
                if (!roadsAbsent && maxRoad > 0f) {
                    float normalised = clamp01(roadProximityMetres[r][c] / maxRoad);
                    roadScore = 1.0f - normalised;
                }

                raw[idx] = W_DRYNESS * drynessScore
                        + W_FIRMS   * firmsScore
                        + W_ROAD    * roadScore;
            }
        }

        // --- pass 2: normalise so weights sum to 1.0 across all combustible cells ---
        // IgnitionSeedSampler uses EnumeratedDistribution which requires non-negative
        // values but handles its own normalisation, so this step is for clarity and
        // to ensure no cell inadvertently dominates due to raw-score scale differences.
        // We keep the raw values — the sampler handles the distribution.
        // However, we do a floor-to-epsilon on combustible cells so no combustible
        // cell has exactly zero probability (every unburned cell can theoretically ignite).
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int idx = r * cols + c;
                if (grid.getState(r, c) != CellStateEnum.NON_COMBUSTIBLE && raw[idx] == 0f) {
                    raw[idx] = 1e-6f; // epsilon floor — prevents dead zero on combustible cells
                }
            }
        }

        log.debug("I(c) built: {} combustible cells weighted across {}×{} grid",
                countCombustible(grid), rows, cols);
        return raw;
    }

    // -------------------------------------------------------------------------

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static int countCombustible(CaGrid grid) {
        int n = 0;
        for (int r = 0; r < grid.rows; r++) {
            for (int c = 0; c < grid.cols; c++) {
                if (grid.getState(r, c) != CellStateEnum.NON_COMBUSTIBLE) n++;
            }
        }
        return n;
    }
}