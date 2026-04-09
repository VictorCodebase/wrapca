package com.victorkithinji.wrap.wrapca.montecarlo;

import com.victorkithinji.wrap.wrapca.config.SimulationConfig;
import com.victorkithinji.wrap.wrapca.correction.SuppressedZoneRegistry;
import com.victorkithinji.wrap.wrapca.grid.CaGrid;
import com.victorkithinji.wrap.wrapca.grid.CellStateEnum;
import com.victorkithinji.wrap.wrapca.ingestion.WindField;
import com.victorkithinji.wrap.wrapca.simulation.CaSpreadEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;

/**
 * Runs the Phase 1 Monte Carlo ensemble.
 *
 * <p>For each of the N runs:
 * <ol>
 *   <li>Deep-copies the base grid.</li>
 *   <li>Seeds one BURNING cell from the pre-sampled seed list.</li>
 *   <li>Runs {@link CaSpreadEngine} forward for the configured horizon.</li>
 *   <li>Records every BURNED or BURNING cell into {@link BurnFrequencyAccumulator}.</li>
 * </ol>
 *
 * <p>All tasks are independent — no shared mutable state between runs.
 * {@link BurnFrequencyAccumulator} is the sole write-shared object and is
 * thread-safe via {@link java.util.concurrent.atomic.AtomicIntegerArray}.
 *
 * <p>Uses a dedicated {@link ForkJoinPool} sized to
 * {@link SimulationConfig#getThreadPoolSize()} to avoid blocking the common pool.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonteCarloEnsembleRunner {

    private final CaSpreadEngine caSpreadEngine;
    private final SimulationConfig simulationConfig;

    /**
     * Runs the full N-run ensemble and returns the populated accumulator.
     *
     * @param baseGrid  initialised grid — not modified; each task works on a deep copy
     * @param windField wind conditions aligned to the grid
     * @param seeds     list of N encoded cell indices to use as ignition seeds,
     *                  one per run; produced by {@link IgnitionSeedSampler}
     * @param masterSeed random seed for per-task RNGs; pass a fixed value for
     *                   reproducible ensembles
     * @return populated accumulator ready for {@link RiskMapAssembler}
     */
    public BurnFrequencyAccumulator run(
            CaGrid baseGrid,
            WindField windField,
            List<Long> seeds,
            long masterSeed) {

        int rows = baseGrid.rows;
        int cols = baseGrid.cols;
        int n    = seeds.size();

        BurnFrequencyAccumulator accumulator = new BurnFrequencyAccumulator(rows, cols);

        // Compute generation count: phase1HorizonHours × 60 / timeStepMinutes
        int generations = (simulationConfig.getPhase1HorizonHours() * 60)
                / simulationConfig.getTimeStepMinutes();

        // Empty suppressed zone registry for Phase 1 — suppression is Phase 2 only
        SuppressedZoneRegistry emptyRegistry = new SuppressedZoneRegistry();

        ForkJoinPool pool = new ForkJoinPool(simulationConfig.getThreadPoolSize());
        try {
            List<ForkJoinTask<?>> tasks = new ArrayList<>(n);

            for (int i = 0; i < n; i++) {
                final long seedCell  = seeds.get(i);
                final long taskSeed  = masterSeed ^ ((long) i * 0x9e3779b97f4a7c15L); // unique per task
                final int  taskIndex = i;

                ForkJoinTask<?> task = pool.submit(() -> {
                    CaGrid copy = baseGrid.deepCopy();

                    // Place the ignition seed — skip if cell is not combustible
                    int seedRow = copy.decodeRow(seedCell);
                    int seedCol = copy.decodeCol(seedCell);
                    if (!copy.inBounds(seedRow, seedCol)
                            || copy.getState(seedRow, seedCol) == CellStateEnum.NON_COMBUSTIBLE) {
                        log.trace("Task {}: seed cell {} is out of bounds or non-combustible, skipping",
                                taskIndex, seedCell);
                        return;
                    }
                    copy.setState(seedRow, seedCol, CellStateEnum.BURNING);

                    caSpreadEngine.run(
                            copy, windField, emptyRegistry, generations, new Random(taskSeed));

                    // Record burn outcome from final grid state.
                    // This is the only correct approach: newlyIgnitedCells never contains
                    // the seed cell (it was BURNING before run() started), so step-by-step
                    // accumulation would silently drop it. Scanning the final grid captures
                    // the seed, all cells that spread and fully burned (BURNED), and any
                    // cells still BURNING at the horizon limit.
                    for (int r = 0; r < copy.rows; r++) {
                        for (int c = 0; c < copy.cols; c++) {
                            CellStateEnum state = copy.getState(r, c);
                            if (state == CellStateEnum.BURNED || state == CellStateEnum.BURNING) {
                                accumulator.record(r, c);
                            }
                        }
                    }
                });

                tasks.add(task);
            }

            // Wait for all tasks
            for (ForkJoinTask<?> task : tasks) {
                task.join();
            }

        } finally {
            pool.shutdown();
        }

        log.info("Monte Carlo ensemble complete: {} runs, {}×{} grid, {} generations each",
                n, rows, cols, generations);
        return accumulator;
    }
}