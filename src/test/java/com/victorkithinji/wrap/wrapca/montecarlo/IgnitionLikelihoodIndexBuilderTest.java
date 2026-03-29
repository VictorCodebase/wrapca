package com.victorkithinji.wrap.wrapca.montecarlo;

import com.victorkithinji.wrap.wrapca.grid.CaGrid;
import com.victorkithinji.wrap.wrapca.grid.CellEnvironment;
import com.victorkithinji.wrap.wrapca.grid.CellStateEnum;
import com.victorkithinji.wrap.wrapca.grid.VegetationTypeEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for IgnitionLikelihoodIndexBuilder.
 *
 * Uses hand-constructed CaGrid instances — no Spring context needed.
 */
class IgnitionLikelihoodIndexBuilderTest {

    private IgnitionLikelihoodIndexBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new IgnitionLikelihoodIndexBuilder();
    }

    // --- helpers ---

    /** Creates an NDMI-only environment cell with the given scaled moisture value. */
    private CellEnvironment env(float ndmi) {
        return new CellEnvironment(0.5f, ndmi, 0f, 0f, VegetationTypeEnum.GRASSLAND);
    }

    /** Builds a uniform 2×2 grid where all cells are UNBURNED with the given NDMI. */
    private CaGrid uniformGrid(int rows, int cols, float ndmi) {
        CellEnvironment[][] envs = new CellEnvironment[rows][cols];
        int[][] stateOrdinals = new int[rows][cols];

        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++){
                envs[r][c] = env(ndmi);
                stateOrdinals[r][c] = CellStateEnum.UNBURNED.ordinal();}

        return new CaGrid(stateOrdinals, envs, rows, cols, 100.0);
    }

    /** Road proximity array with all values set to d. */
    private float[][] uniformRoad(int rows, int cols, float d) {
        float[][] arr = new float[rows][cols];
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++)
                arr[r][c] = d;
        return arr;
    }

    // -----------------------------------------------------------------------

    @Test
    void outputLengthMatchesGridSize() {
        CaGrid grid = uniformGrid(3, 4, 0.1f);
        float[] ic = builder.build(grid, uniformRoad(3, 4, 500f));
        assertThat(ic).hasSize(3 * 4);
    }

    @Test
    void allValuesNonNegative() {
        CaGrid grid = uniformGrid(5, 5, 0.2f);
        float[] ic = builder.build(grid, uniformRoad(5, 5, 200f));
        for (float v : ic) {
            assertThat(v).isGreaterThanOrEqualTo(0f);
        }
    }

    @Test
    void nonCombustibleCellsReceiveZeroWeight() {
        // Build a 2×2 grid: top-left NON_COMBUSTIBLE, rest UNBURNED
        int rows = 2, cols = 2;
        CellEnvironment[][] envs = new CellEnvironment[rows][cols];
        int[][] stateOrdinals = new int[rows][cols];
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++)
                envs[r][c] = env(0.1f);
        stateOrdinals[0][0] = CellStateEnum.NON_COMBUSTIBLE.ordinal();

        CaGrid grid = new CaGrid(stateOrdinals, envs, rows, cols, 100.0);
        float[] ic = builder.build(grid, uniformRoad(rows, cols, 500f));

        assertThat(ic[0]).isEqualTo(0f);          // NON_COMBUSTIBLE → exactly 0
        assertThat(ic[1]).isGreaterThan(0f);       // UNBURNED → positive
        assertThat(ic[2]).isGreaterThan(0f);
        assertThat(ic[3]).isGreaterThan(0f);
    }

    @Test
    void drierCellsScoreHigherThanWetCells() {
        int rows = 1, cols = 2;
        CellEnvironment[][] envs = new CellEnvironment[rows][cols];
        int[][] stateOrdinals = new int[rows][cols];

        envs[0][0] = env(0.03f);  // very dry
        envs[0][1] = env(0.35f);  // near-saturated

        // 2. Set both cells to UNBURNED so the 'builder' actually calculates a score
        stateOrdinals[0][0] = CellStateEnum.UNBURNED.ordinal();
        stateOrdinals[0][1] = CellStateEnum.UNBURNED.ordinal();

        CaGrid grid = new CaGrid(stateOrdinals, envs, rows, cols, 100.0);

        // No roads loaded — road term zeros out
        float[][] noRoads = uniformRoad(rows, cols, Float.MAX_VALUE);
        float[] ic = builder.build(grid, noRoads);

        assertThat(ic[0]).isGreaterThan(ic[1]);
    }

    @Test
    void closerRoadScoresHigher() {
        int rows = 1, cols = 2;
        CellEnvironment[][] envs = new CellEnvironment[rows][cols];
        // Same NDMI — road proximity is the only differentiator
        envs[0][0] = env(0.15f);
        envs[0][1] = env(0.15f);

        int[][] stateOrdinals = new int[rows][cols];
        stateOrdinals[0][0] = CellStateEnum.UNBURNED.ordinal();
        stateOrdinals[0][1] = CellStateEnum.UNBURNED.ordinal();
        CaGrid grid = new CaGrid(stateOrdinals, envs, rows, cols, 100.0);

        float[][] roads = new float[rows][cols];
        roads[0][0] = 50f;    // close to road
        roads[0][1] = 5000f;  // far from road

        float[] ic = builder.build(grid, roads);

        assertThat(ic[0]).isGreaterThan(ic[1]);
    }

    @Test
    void absentRoadDataDoesNotProduceNanOrInfinity() {
        CaGrid grid = uniformGrid(3, 3, 0.2f);
        float[] ic = builder.build(grid, uniformRoad(3, 3, Float.MAX_VALUE));

        for (float v : ic) {
            assertThat(v).isFinite();
            assertThat(v).isGreaterThanOrEqualTo(0f);
        }
    }

    @Test
    void combustibleCellNeverHasExactlyZeroWeight() {
        // Even a very wet, very far-from-road combustible cell must get the epsilon floor
        CaGrid grid = uniformGrid(2, 2, 0.40f); // saturated — dryness score ≈ 0
        float[] ic = builder.build(grid, uniformRoad(2, 2, Float.MAX_VALUE));

        for (float v : ic) {
            assertThat(v).isGreaterThan(0f);
        }
    }
}