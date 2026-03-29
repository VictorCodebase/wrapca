package com.victorkithinji.wrap.wrapca.montecarlo;

import com.victorkithinji.wrap.wrapca.config.SimulationConfig;
import com.victorkithinji.wrap.wrapca.correction.SuppressedZoneRegistry;
import com.victorkithinji.wrap.wrapca.grid.CaGrid;
import com.victorkithinji.wrap.wrapca.grid.CellEnvironment;
import com.victorkithinji.wrap.wrapca.grid.CellStateEnum;
import com.victorkithinji.wrap.wrapca.grid.VegetationTypeEnum;
import com.victorkithinji.wrap.wrapca.ingestion.WindField;
import com.victorkithinji.wrap.wrapca.simulation.CaSpreadEngine;
import com.victorkithinji.wrap.wrapca.simulation.SimulationStepResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration test for the full Monte Carlo pipeline.
 *
 * Uses a fully self-contained synthetic grid (no Spring context, no real data).
 * All dependencies are wired manually so this test runs in isolation.
 *
 * Wind saturation note: as documented in the known-issues table, Pe saturates to 1.0
 * at very low wind speeds for GRASSLAND cells given current ROS parameters. Tests that
 * depend on wind direction differentiating burn spread are marked with a note and will
 * need revisiting once wind parameters are recalibrated. Tests here deliberately avoid
 * asserting directional spread and focus on what is reliably observable now.
 */
class MonteCarloIntegrationTest {

    // --- synthetic grid parameters ---
    private static final int ROWS         = 10;
    private static final int COLS         = 10;
    private static final double CELL_SIZE = 100.0;

    // --- simulation parameters ---
    private static final int   TIME_STEP_MINUTES  = 5;
    private static final int   HORIZON_HOURS      = 2;   // short horizon for fast tests
    private static final int   MONTE_CARLO_RUNS   = 100;
    private static final int   THREAD_POOL_SIZE   = 4;
    private static final float DRY_NDMI           = 0.05f;  // critically dry grass
    private static final long  MASTER_SEED        = 42L;

    // --- wired manually (no Spring) ---
    private CaGrid baseGrid;
    private WindField windField;
    private SimulationConfig config;

    private IgnitionLikelihoodIndexBuilder icBuilder;
    private IgnitionSeedSampler            seedSampler;
    private MonteCarloEnsembleRunner       runner;
    private RiskMapAssembler               assembler;

    @BeforeEach
    void setUp() {
        baseGrid  = buildDryGrassGrid();
        windField = buildCalmWind();          // calm wind — avoids Pe saturation issue
        config    = buildConfig();

        icBuilder   = new IgnitionLikelihoodIndexBuilder();
        seedSampler = new IgnitionSeedSampler();

        // Wire CaSpreadEngine with real SimulationConfig
        CaSpreadEngine engine = new CaSpreadEngine(config);
        runner    = new MonteCarloEnsembleRunner(engine, config);
        assembler = new RiskMapAssembler();
    }

    // -----------------------------------------------------------------------
    // Structural / contract tests
    // -----------------------------------------------------------------------

    @Test
    void ensembleOutputHasCorrectDimensions() {
        float[] ic = icBuilder.build(baseGrid, noRoads());
        List<Long> seeds = seedSampler.sample(baseGrid, ic, MONTE_CARLO_RUNS, MASTER_SEED);
        BurnFrequencyAccumulator acc = runner.run(baseGrid, windField, seeds, MASTER_SEED);
        PhaseOneResult result = assembler.assemble(acc, ic, MONTE_CARLO_RUNS);

        assertThat(result.getRows()).isEqualTo(ROWS);
        assertThat(result.getCols()).isEqualTo(COLS);
        assertThat(result.getDamagePotential()).hasSize(ROWS * COLS);
        assertThat(result.getIgnitionLikelihood()).hasSize(ROWS * COLS);
    }

    @Test
    void damagePotentialValuesAreInUnitRange() {
        float[] ic = icBuilder.build(baseGrid, noRoads());
        List<Long> seeds = seedSampler.sample(baseGrid, ic, MONTE_CARLO_RUNS, MASTER_SEED);
        BurnFrequencyAccumulator acc = runner.run(baseGrid, windField, seeds, MASTER_SEED);
        PhaseOneResult result = assembler.assemble(acc, ic, MONTE_CARLO_RUNS);

        for (float v : result.getDamagePotential()) {
            assertThat(v).isBetween(0f, 1f);
        }
    }

    @Test
    void ignitionLikelihoodPeakIsOne() {
        float[] ic = icBuilder.build(baseGrid, noRoads());
        List<Long> seeds = seedSampler.sample(baseGrid, ic, MONTE_CARLO_RUNS, MASTER_SEED);
        BurnFrequencyAccumulator acc = runner.run(baseGrid, windField, seeds, MASTER_SEED);
        PhaseOneResult result = assembler.assemble(acc, ic, MONTE_CARLO_RUNS);

        float peak = 0f;
        for (float v : result.getIgnitionLikelihood()) if (v > peak) peak = v;
        assertThat(peak).isCloseTo(1.0f, within(1e-5f));
    }

    // -----------------------------------------------------------------------
    // Behaviour tests
    // -----------------------------------------------------------------------

    @Test
    void someCellsBurnAcrossEnsemble() {
        // On a dry grass grid at least some cells must burn — if zero cells burn,
        // something is fundamentally wrong with the CA engine.
        float[] ic = icBuilder.build(baseGrid, noRoads());
        List<Long> seeds = seedSampler.sample(baseGrid, ic, MONTE_CARLO_RUNS, MASTER_SEED);
        BurnFrequencyAccumulator acc = runner.run(baseGrid, windField, seeds, MASTER_SEED);

        int burnedCells = 0;
        for (int r = 0; r < ROWS; r++)
            for (int c = 0; c < COLS; c++)
                if (acc.getCount(r, c) > 0) burnedCells++;

        assertThat(burnedCells).isGreaterThan(0);
    }

    @Test
    void highDamagePotentialCellsExistOnDryGrid() {
        // At least one cell should have been burned in more than 10% of runs
        float[] ic = icBuilder.build(baseGrid, noRoads());
        List<Long> seeds = seedSampler.sample(baseGrid, ic, MONTE_CARLO_RUNS, MASTER_SEED);
        BurnFrequencyAccumulator acc = runner.run(baseGrid, windField, seeds, MASTER_SEED);
        PhaseOneResult result = assembler.assemble(acc, ic, MONTE_CARLO_RUNS);

        float maxDamage = 0f;
        for (float v : result.getDamagePotential()) if (v > maxDamage) maxDamage = v;
        assertThat(maxDamage).isGreaterThan(0.10f);
    }

    @Test
    void reproducibleEnsembleWithSameMasterSeed() {
        float[] ic = icBuilder.build(baseGrid, noRoads());
        List<Long> seeds = seedSampler.sample(baseGrid, ic, MONTE_CARLO_RUNS, MASTER_SEED);

        BurnFrequencyAccumulator acc1 = runner.run(baseGrid, windField, seeds, MASTER_SEED);
        BurnFrequencyAccumulator acc2 = runner.run(baseGrid, windField, seeds, MASTER_SEED);

        PhaseOneResult r1 = assembler.assemble(acc1, ic, MONTE_CARLO_RUNS);
        PhaseOneResult r2 = assembler.assemble(acc2, ic, MONTE_CARLO_RUNS);

        assertThat(r1.getDamagePotential()).isEqualTo(r2.getDamagePotential());
    }

    @Test
    void differentMasterSeedProducesDifferentResults() {
        float[] ic = icBuilder.build(baseGrid, noRoads());
        List<Long> seeds1 = seedSampler.sample(baseGrid, ic, MONTE_CARLO_RUNS, MASTER_SEED);
        List<Long> seeds2 = seedSampler.sample(baseGrid, ic, MONTE_CARLO_RUNS, MASTER_SEED + 1);

        BurnFrequencyAccumulator acc1 = runner.run(baseGrid, windField, seeds1, MASTER_SEED);
        BurnFrequencyAccumulator acc2 = runner.run(baseGrid, windField, seeds2, MASTER_SEED + 1);

        PhaseOneResult r1 = assembler.assemble(acc1, ic, MONTE_CARLO_RUNS);
        PhaseOneResult r2 = assembler.assemble(acc2, ic, MONTE_CARLO_RUNS);

        // Damage potential arrays should differ (extremely unlikely to match with different seeds)
        boolean anyDifference = false;
        float[] d1 = r1.getDamagePotential(), d2 = r2.getDamagePotential();
        for (int i = 0; i < d1.length; i++) {
            if (Math.abs(d1[i] - d2[i]) > 1e-6f) { anyDifference = true; break; }
        }
        assertThat(anyDifference).isTrue();
    }

    @Test
    void nonCombustibleCellNeverBurns() {
        // Build a grid with a NON_COMBUSTIBLE cell at the centre and confirm it never burns
        CaGrid gridWithWater = buildGridWithWaterCentre();
        float[] ic = icBuilder.build(gridWithWater, noRoads());
        List<Long> seeds = seedSampler.sample(gridWithWater, ic, MONTE_CARLO_RUNS, MASTER_SEED);
        BurnFrequencyAccumulator acc = runner.run(gridWithWater, windField, seeds, MASTER_SEED);

        int centreRow = ROWS / 2, centreCol = COLS / 2;
        assertThat(acc.getCount(centreRow, centreCol)).isZero();
    }

    // -----------------------------------------------------------------------
    // Print observable results for manual inspection
    // -----------------------------------------------------------------------

    @Test
    void printEnsembleResultSummary() {
        float[] ic = icBuilder.build(baseGrid, noRoads());
        List<Long> seeds = seedSampler.sample(baseGrid, ic, MONTE_CARLO_RUNS, MASTER_SEED);
        BurnFrequencyAccumulator acc = runner.run(baseGrid, windField, seeds, MASTER_SEED);
        PhaseOneResult result = assembler.assemble(acc, ic, MONTE_CARLO_RUNS);

        System.out.println("\n=== Phase 1 Monte Carlo Result Summary ===");
        System.out.printf("Grid: %d×%d  |  Runs: %d  |  Horizon: %dh  |  TimeStep: %dmin%n",
                ROWS, COLS, MONTE_CARLO_RUNS, HORIZON_HOURS, TIME_STEP_MINUTES);

        // Damage potential heatmap (scaled to 0–9 characters)
        System.out.println("\nDamage Potential Map (0=none, 9=always burned):");
        for (int r = 0; r < ROWS; r++) {
            StringBuilder row = new StringBuilder("  ");
            for (int c = 0; c < COLS; c++) {
                float dp = result.getDamagePotential()[r * COLS + c];
                row.append((int) (dp * 9)).append(' ');
            }
            System.out.println(row);
        }

        // Ignition likelihood heatmap
        System.out.println("\nIgnition Likelihood Map (0=none, 9=highest):");
        for (int r = 0; r < ROWS; r++) {
            StringBuilder row = new StringBuilder("  ");
            for (int c = 0; c < COLS; c++) {
                float il = result.getIgnitionLikelihood()[r * COLS + c];
                row.append((int) (il * 9)).append(' ');
            }
            System.out.println(row);
        }

        // Statistics
        float maxDP = 0f, sumDP = 0f;
        int burnedCellCount = 0;
        for (float v : result.getDamagePotential()) {
            if (v > maxDP) maxDP = v;
            sumDP += v;
            if (v > 0f) burnedCellCount++;
        }
        float meanDP = sumDP / (ROWS * COLS);

        System.out.printf("%n--- Statistics ---%n");
        System.out.printf("Peak damage potential : %.3f%n", maxDP);
        System.out.printf("Mean damage potential : %.3f%n", meanDP);
        System.out.printf("Cells burned ≥1 run   : %d / %d (%.1f%%)%n",
                burnedCellCount, ROWS * COLS,
                100.0 * burnedCellCount / (ROWS * COLS));

        // Wind note
        System.out.println("\nNote: wind is set to calm (0 m/s) in this test to avoid");
        System.out.println("known Pe saturation at very low wind speeds for GRASSLAND.");
        System.out.println("Once wind parameters are recalibrated, rerun with wind enabled.");
    }

    // -----------------------------------------------------------------------
    // Builders
    // -----------------------------------------------------------------------

    private CaGrid buildDryGrassGrid() {
        CellEnvironment[][] envs = new CellEnvironment[ROWS][COLS];
        for (int r = 0; r < ROWS; r++)
            for (int c = 0; c < COLS; c++)
                envs[r][c] = new CellEnvironment(0.5f, DRY_NDMI, 0f, 0f, VegetationTypeEnum.GRASSLAND);
        return new CaGrid(ROWS, COLS, CELL_SIZE, envs);
    }

    private CaGrid buildGridWithWaterCentre() {
        CellEnvironment[][] envs = new CellEnvironment[ROWS][COLS];
        int[][] states = new int[ROWS][COLS];
        for (int r = 0; r < ROWS; r++)
            for (int c = 0; c < COLS; c++)
                envs[r][c] = new CellEnvironment(0.5f, DRY_NDMI, 0f, 0f, VegetationTypeEnum.GRASSLAND);

        int cr = ROWS / 2, cc = COLS / 2;
        envs[cr][cc] = new CellEnvironment(0f, 1f, 0f, 0f, VegetationTypeEnum.WATER);
        states[cr][cc] = CellStateEnum.NON_COMBUSTIBLE.ordinal();

        return new CaGrid(ROWS, COLS, CELL_SIZE, envs, states);
    }

    /** Calm wind — zero speed, direction irrelevant. */
    private WindField buildCalmWind() {
        float[][] speed = new float[ROWS][COLS];     // all zeros
        float[][] dir   = new float[ROWS][COLS];     // all zeros
        return new WindField(speed, dir, ROWS, COLS);
    }

    private SimulationConfig buildConfig() {
        SimulationConfig cfg = new SimulationConfig();
        cfg.setCellSizeMetres(CELL_SIZE);
        cfg.setTimeStepMinutes(TIME_STEP_MINUTES);
        cfg.setMonteCarloRuns(MONTE_CARLO_RUNS);
        cfg.setThreadPoolSize(THREAD_POOL_SIZE);
        cfg.setPhase1HorizonHours(HORIZON_HOURS);
        return cfg;
    }

    private float[][] noRoads() {
        float[][] arr = new float[ROWS][COLS];
        for (int r = 0; r < ROWS; r++)
            for (int c = 0; c < COLS; c++)
                arr[r][c] = Float.MAX_VALUE;
        return arr;
    }
}