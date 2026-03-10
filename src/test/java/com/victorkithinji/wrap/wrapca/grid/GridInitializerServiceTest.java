package com.victorkithinji.wrap.wrapca.grid;

import com.victorkithinji.wrap.wrapca.ingestion.BandData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for GridInitialiserService.
 * <br/> <br/>
 * No Spring context is needed — the service has no Spring dependencies.
 * BandData is constructed inline; no I/O, no mocking frameworks.
 */
class GridInitialiserServiceTest {

    private GridInitializerService service;

    @BeforeEach
    void setUp() {
        service = new GridInitializerService();
    }

    // -----------------------------------------------------------------------
    // 1. Grid dimensions are preserved exactly
    // -----------------------------------------------------------------------

    @Test
    void gridDimensionsMatchBandData() {
        BandData data = uniformGrid(4, 6, VegetationTypeEnum.MONTANE_GRASSLAND);
        CaGrid grid = service.build(data, 100.0);

        assertThat(grid.rows).isEqualTo(4);
        assertThat(grid.cols).isEqualTo(6);
        assertThat(grid.cellSizeMetres).isEqualTo(100.0);
    }

    // -----------------------------------------------------------------------
    // 2. Combustible cells start as UNBURNED
    // -----------------------------------------------------------------------

    @Test
    void combustibleCellsAreUnburned() {
        BandData data = uniformGrid(3, 3, VegetationTypeEnum.AFROMONTANE_FOREST);
        CaGrid grid = service.build(data, 100.0);

        int unburned = CellStateEnum.UNBURNED.ordinal();
        for (int r = 0; r < grid.rows; r++) {
            for (int c = 0; c < grid.cols; c++) {
                assertThat(grid.states[r][c])
                        .as("cell [%d][%d] should be UNBURNED", r, c)
                        .isEqualTo(unburned);
            }
        }
    }

    // -----------------------------------------------------------------------
    // 3. WATER cells become NON_COMBUSTIBLE
    // -----------------------------------------------------------------------

    @Test
    void waterCellsAreNonCombustible() {
        BandData data = uniformGrid(2, 2, VegetationTypeEnum.WATER);
        CaGrid grid = service.build(data, 100.0);

        int nonComb = CellStateEnum.NON_COMBUSTIBLE.ordinal();
        assertThat(grid.states[0][0]).isEqualTo(nonComb);
        assertThat(grid.states[1][1]).isEqualTo(nonComb);
    }

    // -----------------------------------------------------------------------
    // 4. BUILT cells become NON_COMBUSTIBLE
    // -----------------------------------------------------------------------

    @Test
    void builtCellsAreNonCombustible() {
        BandData data = uniformGrid(2, 2, VegetationTypeEnum.BUILT);
        CaGrid grid = service.build(data, 100.0);

        int nonComb = CellStateEnum.NON_COMBUSTIBLE.ordinal();
        assertThat(grid.states[0][0]).isEqualTo(nonComb);
    }

    // -----------------------------------------------------------------------
    // 5. BARE_SOIL cells become NON_COMBUSTIBLE
    // -----------------------------------------------------------------------

    @Test
    void bareSoilCellsAreNonCombustible() {
        BandData data = uniformGrid(2, 2, VegetationTypeEnum.BARE_SOIL);
        CaGrid grid = service.build(data, 100.0);

        int nonComb = CellStateEnum.NON_COMBUSTIBLE.ordinal();
        assertThat(grid.states[0][0]).isEqualTo(nonComb);
    }

    // -----------------------------------------------------------------------
    // 6. Mixed grid: each cell type gets the right initial state
    // -----------------------------------------------------------------------

    @Test
    void mixedGridAssignsCorrectStates() {
        /*
         * Layout (2×3):
         *  [FOREST,  WATER,    SHRUBLAND]
         *  [BUILT,   GRASSLAND, BARE_SOIL]
         */
        int[][] codes = {
                { VegetationTypeEnum.AFROMONTANE_FOREST.ordinal(),
                        VegetationTypeEnum.WATER.ordinal(),
                        VegetationTypeEnum.SHRUBLAND.ordinal() },
                { VegetationTypeEnum.BUILT.ordinal(),
                        VegetationTypeEnum.MONTANE_GRASSLAND.ordinal(),
                        VegetationTypeEnum.BARE_SOIL.ordinal() }
        };
        BandData data = gridWithCodes(2, 3, codes);
        CaGrid grid = service.build(data, 100.0);

        int ub  = CellStateEnum.UNBURNED.ordinal();
        int nc  = CellStateEnum.NON_COMBUSTIBLE.ordinal();

        assertThat(grid.states[0][0]).isEqualTo(ub);   // FOREST  → UNBURNED
        assertThat(grid.states[0][1]).isEqualTo(nc);   // WATER   → NON_COMBUSTIBLE
        assertThat(grid.states[0][2]).isEqualTo(ub);   // SHRUB   → UNBURNED
        assertThat(grid.states[1][0]).isEqualTo(nc);   // BUILT   → NON_COMBUSTIBLE
        assertThat(grid.states[1][1]).isEqualTo(ub);   // GRASS   → UNBURNED
        assertThat(grid.states[1][2]).isEqualTo(nc);   // BARE    → NON_COMBUSTIBLE
    }

    // -----------------------------------------------------------------------
    // 7. CellEnvironment fields are populated from band data
    // -----------------------------------------------------------------------

    @Test
    void cellEnvironmentFieldsMatchBandData() {
        int rows = 1, cols = 1;
        float[][] ndvi   = {{ 0.72f }};
        float[][] ndmi   = {{ 0.31f }};
        float[][] slope  = {{ 0.15f }};
        float[][] aspect = {{ 1.05f }};
        int[][]   codes  = {{ VegetationTypeEnum.SHRUBLAND.ordinal() }};

        BandData data = new BandData(rows, cols, ndvi, ndmi, slope, aspect, codes);
        CaGrid grid = service.build(data, 100.0);

        CellEnvironment env = grid.environment[0][0];
        assertThat(env.getNdvi()).isEqualTo(0.72f);
        assertThat(env.getNdmi()).isEqualTo(0.31f);
        assertThat(env.getSlopeRadians()).isEqualTo(0.15f);
        assertThat(env.getAspectRadians()).isEqualTo(1.05f);
        assertThat(env.getVegetationType()).isEqualTo(VegetationTypeEnum.SHRUBLAND);
    }

    // -----------------------------------------------------------------------
    // 8. Unknown vegetation code falls back to BARE_SOIL (non-combustible)
    // -----------------------------------------------------------------------

    @Test
    void unknownVegetationCodeDefaultsToBareSoil() {
        int[][] codes = {{ 999 }};   // 999 is not a valid ordinal
        BandData data = gridWithCodes(1, 1, codes);
        CaGrid grid = service.build(data, 100.0);

        // Should default to BARE_SOIL → NON_COMBUSTIBLE
        assertThat(grid.states[0][0])
                .isEqualTo(CellStateEnum.NON_COMBUSTIBLE.ordinal());
        assertThat(grid.environment[0][0].getVegetationType())
                .isEqualTo(VegetationTypeEnum.BARE_SOIL);
    }

    // -----------------------------------------------------------------------
    // 9. build() is repeatable — second call returns independent CaGrid
    // -----------------------------------------------------------------------

    @Test
    void buildIsIdempotentAndReturnsIndependentGrids() {
        BandData data = uniformGrid(3, 3, VegetationTypeEnum.MONTANE_GRASSLAND);

        CaGrid first  = service.build(data, 100.0);
        CaGrid second = service.build(data, 100.0);

        // Mutate first grid's state; second should be unaffected
        first.states[1][1] = CellStateEnum.BURNING.ordinal();

        assertThat(second.states[1][1])
                .isEqualTo(CellStateEnum.UNBURNED.ordinal());
    }

    // -----------------------------------------------------------------------
    // 10. Single-cell grid works without ArrayIndexOutOfBounds
    // -----------------------------------------------------------------------

    @Test
    void singleCellGridDoesNotThrow() {
        BandData data = uniformGrid(1, 1, VegetationTypeEnum.AFROMONTANE_FOREST);
        assertThatCode(() -> service.build(data, 100.0)).doesNotThrowAnyException();
    }

    // -----------------------------------------------------------------------
    // Builders
    // -----------------------------------------------------------------------

    /** Uniform grid where every cell has the same vegetation type. */
    private BandData uniformGrid(int rows, int cols, VegetationTypeEnum vegType) {
        int[][] codes = new int[rows][cols];
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++)
                codes[r][c] = vegType.ordinal();
        return gridWithCodes(rows, cols, codes);
    }

    /** Grid with caller-supplied vegetation code array; all env values are non-zero defaults. */
    private BandData gridWithCodes(int rows, int cols, int[][] codes) {
        float[][] ndvi   = filled(rows, cols, 0.5f);
        float[][] ndmi   = filled(rows, cols, 0.2f);
        float[][] slope  = filled(rows, cols, 0.1f);
        float[][] aspect = filled(rows, cols, 0.8f);
        return new BandData(rows, cols, ndvi, ndmi, slope, aspect, codes);
    }

    private float[][] filled(int rows, int cols, float value) {
        float[][] arr = new float[rows][cols];
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++)
                arr[r][c] = value;
        return arr;
    }
}