package com.victorkithinji.wrap.wrapca.simulation;

import com.victorkithinji.wrap.wrapca.config.SimulationConfig;
import com.victorkithinji.wrap.wrapca.correction.SuppressedZoneRegistry;
import com.victorkithinji.wrap.wrapca.grid.CaGrid;
import com.victorkithinji.wrap.wrapca.grid.CellEnvironment;
import com.victorkithinji.wrap.wrapca.grid.CellStateEnum;
import com.victorkithinji.wrap.wrapca.ingestion.WindField;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class CaSpreadEngineTest {

    private CaSpreadEngine        engine;
    private SuppressedZoneRegistry emptyRegistry;

    @BeforeEach
    void setUp() {
        SimulationConfig config = new SimulationConfig();
        config.setCellSizeMetres(100.0);
        config.setTimeStepMinutes(5);

        IgnitionProbabilityResolver resolver = new IgnitionProbabilityResolver(config);
        engine = new CaSpreadEngine(resolver, config);

        emptyRegistry = new SuppressedZoneRegistry();
    }

    // -------------------------------------------------------------------------
    // No fire → empty result list
    // -------------------------------------------------------------------------

    @Test
    void returnsEmptyListWhenNoFire() {
        CaGrid    grid = GridFactory.unburnedFlat(5, 5);
        WindField wind = GridFactory.calmWind(5, 5);

        List<SimulationStepResult> results =
                engine.run(grid, wind, emptyRegistry, 10, deterministicRng(1L));

        assertThat(results).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Source cell advances BURNING → BURNED each generation
    // -------------------------------------------------------------------------

    @Test
    void burningCellBecomesAfterFirstGeneration() {
        CaGrid    grid = GridFactory.singleBurningCell(3, 3, 1, 1);
        WindField wind = GridFactory.calmWind(3, 3);

        engine.run(grid, wind, emptyRegistry, 1, deterministicRng(1L));

        assertThat(grid.getState(1, 1)).isEqualTo(CellStateEnum.BURNED);
    }

    // -------------------------------------------------------------------------
    // Fire spreads to at least one neighbour over enough generations (dry fuel)
    // -------------------------------------------------------------------------

    @Test
    void fireSpreadsToNeighboursOverTime() {
        // 5×5 grid, dry grassland, centre BURNING. Run 10 generations with
        // deterministic RNG seeded for spreading. At least one neighbour must ignite.
        CaGrid    grid = GridFactory.singleBurningCell(5, 5, 2, 2);
        WindField wind = GridFactory.southerlyWind(5, 5, 5.0f);

        engine.run(grid, wind, emptyRegistry, 10, alwaysIgniteRng());

        // At least one cell other than the origin must be BURNED or BURNING
        boolean spread = false;
        for (int r = 0; r < 5; r++) {
            for (int c = 0; c < 5; c++) {
                if (r == 2 && c == 2) continue;
                CellStateEnum s = grid.getState(r, c);
                if (s == CellStateEnum.BURNED || s == CellStateEnum.BURNING) {
                    spread = true;
                }
            }
        }
        assertThat(spread).isTrue();
    }

    // -------------------------------------------------------------------------
    // Generation counter is sequential and zero-based
    // -------------------------------------------------------------------------

    @Test
    void generationCountersAreSequentialFromZero() {
        CaGrid    grid = GridFactory.singleBurningCell(5, 5, 2, 2);
        WindField wind = GridFactory.calmWind(5, 5);

        List<SimulationStepResult> results =
                engine.run(grid, wind, emptyRegistry, 5, alwaysIgniteRng());

        for (int i = 0; i < results.size(); i++) {
            assertThat(results.get(i).getGeneration()).isEqualTo(i);
        }
    }

    // -------------------------------------------------------------------------
    // Result count never exceeds requested generations
    // -------------------------------------------------------------------------

    @Test
    void resultCountNeverExceedsRequestedGenerations() {
        CaGrid    grid = GridFactory.singleBurningCell(5, 5, 2, 2);
        WindField wind = GridFactory.southerlyWind(5, 5, 5.0f);

        int requested = 4;
        List<SimulationStepResult> results =
                engine.run(grid, wind, emptyRegistry, requested, alwaysIgniteRng());

        assertThat(results.size()).isLessThanOrEqualTo(requested);
    }

    // -------------------------------------------------------------------------
    // Timestamps are non-decreasing
    // -------------------------------------------------------------------------

    @Test
    void timestampsAreNonDecreasing() {
        CaGrid    grid = GridFactory.singleBurningCell(5, 5, 2, 2);
        WindField wind = GridFactory.southerlyWind(5, 5, 5.0f);

        List<SimulationStepResult> results =
                engine.run(grid, wind, emptyRegistry, 5, alwaysIgniteRng());

        for (int i = 1; i < results.size(); i++) {
            Instant prev = results.get(i - 1).getTimestamp();
            Instant curr = results.get(i).getTimestamp();
            assertThat(curr).isAfterOrEqualTo(prev);
        }
    }

    // -------------------------------------------------------------------------
    // Suppressed cell is not ignited
    // -------------------------------------------------------------------------

    @Test
    void suppressedFrontierCellIsNeverIgnited() {
        // 3×3, centre BURNING. Suppress all frontier cells.
        CaGrid grid = GridFactory.singleBurningCell(3, 3, 1, 1);
        WindField wind = GridFactory.southerlyWind(3, 3, 10.0f);

        SuppressedZoneRegistry registry = new SuppressedZoneRegistry();
        // Suppress all 8 neighbours
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                if (r == 1 && c == 1) continue;
                registry.register(grid.encodeIndex(r, c),
                        Instant.now().plusSeconds(3600));
            }
        }

        engine.run(grid, wind, registry, 5, alwaysIgniteRng());

        // No neighbour should have ignited
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                if (r == 1 && c == 1) continue;
                assertThat(grid.getState(r, c))
                        .as("Cell (%d,%d) must remain UNBURNED", r, c)
                        .isEqualTo(CellStateEnum.UNBURNED);
            }
        }
    }

    // -------------------------------------------------------------------------
    // NON_COMBUSTIBLE cells are never ignited
    // -------------------------------------------------------------------------

    @Test
    void nonCombustibleCellsAreNeverIgnited() {
        // Surround the BURNING centre with NON_COMBUSTIBLE cells
        CaGrid grid = GridFactory.unburnedFlat(3, 3);
        grid.setState(1, 1, CellStateEnum.BURNING);
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                if (r == 1 && c == 1) continue;
                grid.setState(r, c, CellStateEnum.NON_COMBUSTIBLE);
                grid.environment[r][c] = GridFactory.water();
            }
        }
        WindField wind = GridFactory.southerlyWind(3, 3, 10.0f);

        engine.run(grid, wind, emptyRegistry, 5, alwaysIgniteRng());

        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                if (r == 1 && c == 1) continue;
                assertThat(grid.getState(r, c))
                        .as("Cell (%d,%d) must stay NON_COMBUSTIBLE", r, c)
                        .isEqualTo(CellStateEnum.NON_COMBUSTIBLE);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Synchronous CA semantics: newly ignited cell does not spread in same gen
    // -------------------------------------------------------------------------

    @Test
    void newlyIgnitedCellDoesNotSpreadInSameGeneration() {
        // 1×5 line: [UNBURNED, UNBURNED, BURNING, UNBURNED, UNBURNED]
        // Gen 0: (0,1) ignites from (0,2). (0,3) must not ignite in same gen
        // because (0,1) was still UNBURNED at the start of gen 0.
        CaGrid grid = GridFactory.unburnedFlat(1, 5);
        grid.setState(0, 2, CellStateEnum.BURNING);
        WindField wind = GridFactory.calmWind(1, 5);

        // Run exactly 1 generation
        engine.run(grid, wind, emptyRegistry, 1, alwaysIgniteRng());

        // (0,2) must now be BURNED
        assertThat(grid.getState(0, 2)).isEqualTo(CellStateEnum.BURNED);

        // (0,3) may have been reached by (0,2)'s fire in gen 0 as a frontier cell,
        // but it must NOT be BURNED yet (it can be BURNING at most — that's fine,
        // it was a frontier cell of (0,2) which was BURNING at step start)
        // The key assertion: (0,4) must still be UNBURNED after gen 0,
        // because (0,3) had not yet started BURNING when gen 0 evaluated.
        assertThat(grid.getState(0, 4)).isEqualTo(CellStateEnum.UNBURNED);
    }

    // -------------------------------------------------------------------------
    // deepCopy independence — mutations do not affect original
    // -------------------------------------------------------------------------

    @Test
    void runOnDeepCopyDoesNotMutateOriginal() {
        CaGrid    original = GridFactory.singleBurningCell(5, 5, 2, 2);
        CaGrid    copy     = original.deepCopy();
        WindField wind     = GridFactory.southerlyWind(5, 5, 5.0f);

        engine.run(copy, wind, emptyRegistry, 5, alwaysIgniteRng());

        // Original centre should still be BURNING (engine didn't touch it)
        assertThat(original.getState(2, 2)).isEqualTo(CellStateEnum.BURNING);
        // Verify copy has progressed
        assertThat(copy.getState(2, 2)).isEqualTo(CellStateEnum.BURNED);
    }

    // -------------------------------------------------------------------------
    // Reproducibility: same seed → same result
    // -------------------------------------------------------------------------

    @Test
    void sameRngSeedProducesSameResults() {
        CaGrid    gridA = GridFactory.singleBurningCell(7, 7, 3, 3);
        CaGrid    gridB = gridA.deepCopy();
        WindField wind  = GridFactory.southerlyWind(7, 7, 4.0f);

        List<SimulationStepResult> resultsA =
                engine.run(gridA, wind, emptyRegistry, 8, new Random(42L));
        List<SimulationStepResult> resultsB =
                engine.run(gridB, wind, emptyRegistry, 8, new Random(42L));

        assertThat(resultsA).hasSameSizeAs(resultsB);
        for (int i = 0; i < resultsA.size(); i++) {
            assertThat(resultsA.get(i).getNewlyIgnitedCells())
                    .isEqualTo(resultsB.get(i).getNewlyIgnitedCells());
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** RNG that always returns 0.0 — every probabilistic roll succeeds. */
    private static Random alwaysIgniteRng() {
        return new Random() {
            @Override public double nextDouble() { return 0.0; }
        };
    }

    /** Standard seeded RNG for reproducibility tests. */
    private static Random deterministicRng(long seed) {
        return new Random(seed);
    }
}