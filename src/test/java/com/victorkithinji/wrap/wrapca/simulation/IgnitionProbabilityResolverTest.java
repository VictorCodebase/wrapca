package com.victorkithinji.wrap.wrapca.simulation;

import com.victorkithinji.wrap.wrapca.config.SimulationConfig;
import com.victorkithinji.wrap.wrapca.grid.CaGrid;
import com.victorkithinji.wrap.wrapca.grid.CellEnvironment;
import com.victorkithinji.wrap.wrapca.grid.CellStateEnum;
import com.victorkithinji.wrap.wrapca.grid.VegetationTypeEnum;
import com.victorkithinji.wrap.wrapca.ingestion.WindField;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IgnitionProbabilityResolver depends on SimulationConfig (for timeStepMinutes)
 * and the Rothermel static utilities. No Spring context needed — SimulationConfig
 * is constructed directly and its timeStepMinutes field set via reflection-free
 * setter (Lombok @Data generates one).
 */
class IgnitionProbabilityResolverTest {

    private IgnitionProbabilityResolver resolver;
    private SimulationConfig            config;

    @BeforeEach
    void setUp() {
        config = new SimulationConfig();
        config.setTimeStepMinutes(5);
        resolver = new IgnitionProbabilityResolver(config);
    }

    // -------------------------------------------------------------------------
    // No BURNING neighbours → probability must be 0
    // -------------------------------------------------------------------------

    @Test
    void returnsZeroWhenNoBurningNeighbours() {
        CaGrid     grid = GridFactory.unburnedFlat(3, 3);
        WindField  wind = GridFactory.calmWind(3, 3);

        double p = resolver.resolve(1, 1, grid, wind, config.getTimeStepMinutes());
        assertThat(p).isEqualTo(0.0);
    }

    // -------------------------------------------------------------------------
    // Wet fuel → probability must be 0 (moisture above extinction)
    // -------------------------------------------------------------------------

    @Test
    void returnsZeroWhenSourceCellFuelTooWet() {
        // Source (burning) cell is wet — ROS will be 0 for all neighbours
        CaGrid grid = GridFactory.unburnedFlat(3, 3);
        // Replace all cells with wet grassland, then set centre to BURNING
        CellEnvironment wet = GridFactory.wetGrassland();
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                grid.environment[r][c] = wet;
            }
        }
        grid.setState(1, 1, CellStateEnum.BURNING);

        WindField wind = GridFactory.calmWind(3, 3);
        double p = resolver.resolve(0, 1, grid, wind, config.getTimeStepMinutes());
        assertThat(p).isEqualTo(0.0);
    }

    // -------------------------------------------------------------------------
    // Dry fuel, calm wind, flat terrain → probability strictly between 0 and 1
    // -------------------------------------------------------------------------

    @Test
    void returnsPositiveProbabilityForDryFuelCalmWindFlat() {
        // (1,1) BURNING, resolve for (0,1) which is directly N — directionIndex 0
        CaGrid grid = GridFactory.singleBurningCell(3, 3, 1, 1);
        WindField wind = GridFactory.calmWind(3, 3);

        double p = resolver.resolve(0, 1, grid, wind, config.getTimeStepMinutes());
        assertThat(p).isGreaterThan(0.0).isLessThanOrEqualTo(1.0);
    }

    // -------------------------------------------------------------------------
    // Multiple BURNING neighbours → combined probability >= each individual Pe
    // -------------------------------------------------------------------------

    @Test
    void multipleBurningNeighboursYieldHigherProbabilityThanSingle() {
        // 5×5, resolve for centre (2,2).
        // Case A: only (1,2) BURNING (one cardinal neighbour from N)
        CaGrid gridA = GridFactory.unburnedFlat(5, 5);
        gridA.setState(1, 2, CellStateEnum.BURNING);
        WindField wind = GridFactory.calmWind(5, 5);

        double pSingle = resolver.resolve(2, 2, gridA, wind, config.getTimeStepMinutes());

        // Case B: (1,2) and (3,2) both BURNING
        CaGrid gridB = GridFactory.unburnedFlat(5, 5);
        gridB.setState(1, 2, CellStateEnum.BURNING);
        gridB.setState(3, 2, CellStateEnum.BURNING);

        double pDouble = resolver.resolve(2, 2, gridB, wind, config.getTimeStepMinutes());

        assertThat(pDouble).isGreaterThan(pSingle);
    }

    // -------------------------------------------------------------------------
    // Result always in [0, 1]
    // -------------------------------------------------------------------------

    @Test
    void probabilityIsAlwaysBetweenZeroAndOne() {
        // Extreme conditions: strong wind, dry fuel, all 8 neighbours BURNING
        CaGrid grid = GridFactory.unburnedFlat(3, 3);
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                if (r == 1 && c == 1) continue;
                grid.setState(r, c, CellStateEnum.BURNING);
            }
        }
        WindField wind = GridFactory.southerlyWind(3, 3, 10.0f);

        double p = resolver.resolve(1, 1, grid, wind, config.getTimeStepMinutes());
        assertThat(p).isGreaterThanOrEqualTo(0.0).isLessThanOrEqualTo(1.0);
    }

    // -------------------------------------------------------------------------
    // Wind direction influences probability (downwind cell ignites more easily)
    // -------------------------------------------------------------------------

    @Test
    void downwindCellHasHigherProbabilityThanUpwindCell() {
        // Southerly wind (blows North). BURNING cell at (2,2).
        // (1,2) is to the North of (2,2) — downwind.
        // (3,2) is to the South of (2,2) — upwind.
        CaGrid grid = GridFactory.unburnedFlat(5, 5);
        grid.setState(2, 2, CellStateEnum.BURNING);
        WindField wind = GridFactory.southerlyWind(5, 5, 5.0f);

        double pDownwind = resolver.resolve(1, 2, grid, wind, config.getTimeStepMinutes());
        double pUpwind   = resolver.resolve(3, 2, grid, wind, config.getTimeStepMinutes());

        assertThat(pDownwind).isGreaterThan(pUpwind);
    }

    // -------------------------------------------------------------------------
    // Non-combustible target cell — resolver should still return a value
    // (the engine guards on state before calling; resolver itself doesn't check)
    // -------------------------------------------------------------------------

    @Test
    void resolverDoesNotThrowForNonCombustibleTargetCell() {
        // Engine guarantees this won't be called for NON_COMBUSTIBLE, but resolver
        // must not blow up if it were — it simply sees no BURNING neighbours or
        // returns a value based on surrounding state.
        CaGrid grid = GridFactory.singleBurningCell(3, 3, 1, 1);
        grid.setState(0, 1, CellStateEnum.NON_COMBUSTIBLE);
        grid.environment[0][1] = GridFactory.water();

        WindField wind = GridFactory.calmWind(3, 3);

        // Should not throw
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> resolver.resolve(0, 1, grid, wind, config.getTimeStepMinutes()));
    }
}