package com.victorkithinji.wrap.wrapca.simulation;

import com.victorkithinji.wrap.wrapca.config.SimulationConfig;
import com.victorkithinji.wrap.wrapca.correction.SuppressedZoneRegistry;
import com.victorkithinji.wrap.wrapca.grid.CaGrid;
import com.victorkithinji.wrap.wrapca.grid.CellStateEnum;
import com.victorkithinji.wrap.wrapca.ingestion.WindField;
import com.victorkithinji.wrap.wrapca.rothermel.FuelModelResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Random;

import static com.victorkithinji.wrap.wrapca.simulation.GridTestFactory.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioural tests for CaSpreadEngine.
 *
 * These tests use a seeded Random so results are deterministic. The strategy
 * per test is:
 *   - Force a "certain ignition" scenario (Random always returns 0.0, so any
 *     Pe > 0 triggers ignition) or a "certain survival" scenario (Random always
 *     returns 1.0, so nothing ever ignites) to make assertions binary.
 *   - Verify the state machine: UNBURNED → BURNING → BURNED, no re-ignition.
 *   - Verify suppression and wet-fuel short-circuits produce no spread.
 */
class CaSpreadEngineTest {

    private CaSpreadEngine engine;
    private SuppressedZoneRegistry registry;

    // A Random that always returns 0.0 — every Pe > 0 causes ignition
    private static final Random ALWAYS_IGNITE = new Random() {
        @Override public double nextDouble() { return 0.0; }
    };

    // A Random that always returns 1.0 — nothing ever ignites
    private static final Random NEVER_IGNITE = new Random() {
        @Override public double nextDouble() { return 1.0; }
    };

    @BeforeEach
    void setUp() {
        SimulationConfig config = new SimulationConfig();
        config.setTimeStepMinutes(5);
        config.setCellSizeMetres(CELL_SIZE);

        IgnitionProbabilityResolver ignitionResolver = new IgnitionProbabilityResolver();

        engine   = new CaSpreadEngine(ignitionResolver, config);
        registry = new SuppressedZoneRegistry();
    }

    // -------------------------------------------------------------------------
    // State machine correctness
    // -------------------------------------------------------------------------

    @Test
    void burningCellTransitionsToBurnedAfterOneStep() {
        // 3×3 grid, centre burning, NEVER_IGNITE so nothing spreads.
        // After one step the original BURNING cell must be BURNED.
        CaGrid grid = unburnedGrid(3, 3, 0.2f);
        ignite(grid, 1, 1);
        WindField wind = calmWind(3, 3);

        engine.run(grid, wind, registry, 1, NEVER_IGNITE);

        assertThat(grid.states[1][1]).isEqualTo(CellStateEnum.BURNED.ordinal());
    }

    @Test
    void burnedCellNeverReIgnites() {
        // Set (1,1) to BURNED before the run. Surround it with BURNING cells.
        // ALWAYS_IGNITE is used — but BURNED should never become BURNING.
        CaGrid grid = unburnedGrid(3, 3, 0.2f);
        grid.states[1][1] = CellStateEnum.BURNED.ordinal();
        ignite(grid, 0, 0);
        ignite(grid, 0, 1);
        ignite(grid, 1, 0);
        WindField wind = calmWind(3, 3);

        engine.run(grid, wind, registry, 3, ALWAYS_IGNITE);

        assertThat(grid.states[1][1]).isEqualTo(CellStateEnum.BURNED.ordinal());
    }

    @Test
    void nonCombustibleCellNeverIgnites() {
        CaGrid grid = unburnedGrid(3, 3, 0.2f);
        ignite(grid, 1, 0);
        grid.states[1][1] = CellStateEnum.NON_COMBUSTIBLE.ordinal();
        WindField wind = calmWind(3, 3);

        engine.run(grid, wind, registry, 5, ALWAYS_IGNITE);

        assertThat(grid.states[1][1]).isEqualTo(CellStateEnum.NON_COMBUSTIBLE.ordinal());
    }

    // -------------------------------------------------------------------------
    // Spread behaviour
    // -------------------------------------------------------------------------

    @Test
    void fireSpreadsToBothSidesOfSingleBurningCell() {
        // 1×5 row, centre (0,2) burning.  ALWAYS_IGNITE → neighbours (0,1) and
        // (0,3) must have ignited within 2 steps.
        CaGrid grid = unburnedGrid(1, 5, 0.1f);
        ignite(grid, 0, 2);
        WindField wind = calmWind(1, 5);

        engine.run(grid, wind, registry, 2, ALWAYS_IGNITE);

        // After step 1: (0,2) BURNED, (0,1) and (0,3) BURNING.
        // After step 2: those become BURNED.
        assertThat(grid.states[0][1]).isEqualTo(CellStateEnum.BURNED.ordinal());
        assertThat(grid.states[0][3]).isEqualTo(CellStateEnum.BURNED.ordinal());
    }

    @Test
    void noSpreadWhenRandAlwaysExceedsProbability() {
        CaGrid grid = unburnedGrid(5, 5, 0.2f);
        ignite(grid, 2, 2);
        WindField wind = calmWind(5, 5);

        engine.run(grid, wind, registry, 5, NEVER_IGNITE);

        // Only the original cell should have changed state (BURNING → BURNED).
        // All others remain UNBURNED.
        int unburned = countCellsInState(grid, CellStateEnum.UNBURNED);
        assertThat(unburned).isEqualTo(24); // 25 - 1
    }

    @Test
    void stepResultListSizeMatchesGenerationsRun() {
        CaGrid grid = unburnedGrid(5, 5, 0.2f);
        ignite(grid, 2, 2);
        WindField wind = calmWind(5, 5);

        List<SimulationStepResult> results =
                engine.run(grid, wind, registry, 4, NEVER_IGNITE);

        // Fire dies after step 1 (nothing spread), so engine stops early.
        // Expect at most 4 results.
        assertThat(results.size()).isLessThanOrEqualTo(4);
    }

    @Test
    void stepResultGenerationCountersAreSequential() {
        CaGrid grid = unburnedGrid(7, 7, 0.2f);
        ignite(grid, 3, 3);
        WindField wind = uniformWind(7, 7, 5.0f, 180.0f);

        List<SimulationStepResult> results =
                engine.run(grid, wind, registry, 5, ALWAYS_IGNITE);

        for (int i = 0; i < results.size(); i++) {
            assertThat(results.get(i).getGeneration()).isEqualTo(i);
        }
    }

    @Test
    void stepResultTimestampsAreNonDecreasing() {
        CaGrid grid = unburnedGrid(7, 7, 0.2f);
        ignite(grid, 3, 3);
        WindField wind = uniformWind(7, 7, 5.0f, 180.0f);

        List<SimulationStepResult> results =
                engine.run(grid, wind, registry, 5, ALWAYS_IGNITE);

        for (int i = 1; i < results.size(); i++) {
            Instant prev = results.get(i - 1).getTimestamp();
            Instant curr = results.get(i).getTimestamp();
            assertThat(curr).isAfterOrEqualTo(prev);
        }
    }

    // -------------------------------------------------------------------------
    // Wet fuel — no spread when moisture exceeds extinction threshold
    // -------------------------------------------------------------------------

    @Test
    void noSpreadWhenFuelIsSaturated() {
        // GRASSLAND moistureOfExtinction in the fuel model JSON is 0.25.
        // Setting ndmi = 0.9 (90 % moisture) must produce zero ROS → Pe = 0 → no spread.
        // We use ALWAYS_IGNITE to confirm it's the physics killing spread, not the RNG.
        CaGrid grid = unburnedGrid(5, 5, 0.9f); // very wet
        ignite(grid, 2, 2);
        WindField wind = uniformWind(5, 5, 5.0f, 180.0f);

        engine.run(grid, wind, registry, 5, ALWAYS_IGNITE);

        int unburned = countCellsInState(grid, CellStateEnum.UNBURNED);
        assertThat(unburned).isEqualTo(24);
    }

    // -------------------------------------------------------------------------
    // Suppression
    // -------------------------------------------------------------------------

    @Test
    void suppressedCellDoesNotIgniteEvenWithAlwaysIgniteRng() {
        CaGrid grid = unburnedGrid(3, 3, 0.2f);
        ignite(grid, 1, 0);
        WindField wind = calmWind(3, 3);

        // Suppress (1,1) for the next hour
        long targetIdx = (long) 1 * 3 + 1; // row=1, col=1, cols=3
        registry.register(targetIdx, Instant.now().plusSeconds(3600));

        engine.run(grid, wind, registry, 3, ALWAYS_IGNITE);

        // (1,1) must remain UNBURNED (suppressed) despite always-ignite RNG
        assertThat(grid.states[1][1]).isEqualTo(CellStateEnum.UNBURNED.ordinal());
    }

    @Test
    void expiredSuppressionAllowsIgnition() {
        CaGrid grid = unburnedGrid(3, 3, 0.1f);
        ignite(grid, 1, 0);
        WindField wind = calmWind(3, 3);

        // Expiry already in the past — suppression is inactive
        long targetIdx = (long) 1 * 3 + 1;
        registry.register(targetIdx, Instant.now().minusSeconds(1));

        engine.run(grid, wind, registry, 2, ALWAYS_IGNITE);

        // Should have spread normally
        assertThat(grid.states[1][1]).isNotEqualTo(CellStateEnum.UNBURNED.ordinal());
    }

    // -------------------------------------------------------------------------
    // Early termination
    // -------------------------------------------------------------------------

    @Test
    void engineTerminatesEarlyWhenFrontierExhausted() {
        // Tiny 3×3 grid, ALWAYS_IGNITE.  After enough steps everything is BURNED.
        // Run for 20 steps — should stop well before that.
        CaGrid grid = unburnedGrid(3, 3, 0.1f);
        ignite(grid, 1, 1);
        WindField wind = uniformWind(3, 3, 5.0f, 180.0f);

        List<SimulationStepResult> results =
                engine.run(grid, wind, registry, 20, ALWAYS_IGNITE);

        assertThat(results.size()).isLessThan(20);
        assertThat(countCellsInState(grid, CellStateEnum.UNBURNED)).isEqualTo(0);
    }
}