package com.victorkithinji.wrap.wrapca.simulation;

import com.victorkithinji.wrap.wrapca.config.SimulationConfig;
import com.victorkithinji.wrap.wrapca.correction.SuppressedZoneRegistry;
import com.victorkithinji.wrap.wrapca.grid.CaGrid;
import com.victorkithinji.wrap.wrapca.grid.CellStateEnum;
import com.victorkithinji.wrap.wrapca.ingestion.WindField;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Core Cellular Automata spread engine.
 *
 * One generation step:
 *   1. Snapshot the current frontier (we evaluate cells from this snapshot,
 *      not from a set that may be modified mid-step).
 *   2. For each frontier cell, ask IgnitionProbabilityResolver for P(ignition).
 *   3. Roll a random number — if rand < P(ignition) the cell ignites.
 *   4. After all cells are evaluated, apply state transitions atomically:
 *        UNBURNED → BURNING for newly ignited cells.
 *   5. Advance BURNING → BURNED for cells that have been burning for one full
 *      step (simple one-step burn duration).
 *   6. Update the frontier tracker.
 *   7. Return a SimulationStepResult.
 *
 * Thread safety: one engine instance per simulation run. Monte Carlo uses
 * independent instances with independent grid copies — no shared state.
 *
 * The SuppressedZoneRegistry is checked before any frontier cell is evaluated;
 * suppressed cells are treated as NON_COMBUSTIBLE for the duration.
 */
@Component
public class CaSpreadEngine {

    private final IgnitionProbabilityResolver ignitionResolver;
    private final SimulationConfig            config;

    public CaSpreadEngine(IgnitionProbabilityResolver ignitionResolver,
                          SimulationConfig config) {
        this.ignitionResolver = ignitionResolver;
        this.config           = config;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Run the engine for a fixed number of generations.
     *
     * @param grid               mutable grid — will be modified in place
     * @param windField          wind conditions for this run
     * @param suppressedZones    suppressed-zone registry; pass a blank registry
     *                           for Phase 1 Monte Carlo runs
     * @param generations        number of generation steps to execute
     * @param rng                caller-supplied RNG (enables deterministic tests)
     * @return ordered list of step results, one per generation
     */
    public List<SimulationStepResult> run(CaGrid grid,
                                          WindField windField,
                                          SuppressedZoneRegistry suppressedZones,
                                          int generations,
                                          Random rng) {

        ActiveCellFrontierTracker frontier = new ActiveCellFrontierTracker();
        frontier.seedFromGrid(grid);

        List<SimulationStepResult> results = new ArrayList<>(generations);
        double timeStepMin = config.getTimeStepMinutes();

        for (int gen = 0; gen < generations; gen++) {

            if (frontier.isEmpty()) {
                break; // fire has died out — no point continuing
            }

            Set<Long> frontierSnapshot = new HashSet<>(frontier.getFrontier());
            Set<Long> newlyIgnited     = new HashSet<>();

            // --- evaluate each frontier cell ---
            for (long idx : frontierSnapshot) {
                int row = decodeRow(idx, grid.cols);
                int col = decodeCol(idx, grid.cols);

                // Skip cells in a suppressed zone (treated as non-combustible)
                if (suppressedZones.isActive(idx)) {
                    continue;
                }

                double p = ignitionResolver.resolve(row, col, grid, windField, timeStepMin);

                if (rng.nextDouble() < p) {
                    newlyIgnited.add(idx);
                }
            }

            // --- advance BURNING → BURNED (cells that were burning last step) ---
            Set<Long> burnedOut = advanceBurning(grid);

            // --- apply UNBURNED → BURNING transitions ---
            applyIgnitions(newlyIgnited, grid);

            // --- update frontier ---
            frontier.onBurnOut(burnedOut, grid);
            frontier.onIgnition(newlyIgnited, grid);

            results.add(new SimulationStepResult(
                    Collections.unmodifiableSet(newlyIgnited),
                    gen,
                    Instant.now()
            ));
        }

        return results;
    }

    /**
     * Convenience overload that creates a fresh Random internally.
     * Used for production runs; pass an explicit RNG for reproducible tests.
     */
    public List<SimulationStepResult> run(CaGrid grid,
                                          WindField windField,
                                          SuppressedZoneRegistry suppressedZones,
                                          int generations) {
        return run(grid, windField, suppressedZones, generations, new Random());
    }

    // -------------------------------------------------------------------------
    // State transition helpers
    // -------------------------------------------------------------------------

    /**
     * Transitions all currently BURNING cells to BURNED.
     * Returns the set of cells that just burned out (needed to update frontier).
     *
     * Simple one-step burn duration: a cell that is BURNING at the start of a
     * generation is BURNED by the end. This keeps the model consistent with the
     * proposal's single-state-per-step semantics.
     */
    private Set<Long> advanceBurning(CaGrid grid) {
        int[][] states = grid.states;
        int rows = grid.rows;
        int cols = grid.cols;
        Set<Long> burnedOut = new HashSet<>();

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (states[r][c] == CellStateEnum.BURNING.ordinal()) {
                    states[r][c] = CellStateEnum.BURNED.ordinal();
                    burnedOut.add(ActiveCellFrontierTracker.encode(r, c, cols));
                }
            }
        }
        return burnedOut;
    }

    /** Writes BURNING state for each newly ignited cell. */
    private void applyIgnitions(Set<Long> newlyIgnited, CaGrid grid) {
        int[][] states = grid.states;
        int cols = grid.cols;

        for (long idx : newlyIgnited) {
            int r = decodeRow(idx, cols);
            int c = decodeCol(idx, cols);
            states[r][c] = CellStateEnum.BURNING.ordinal();
        }
    }

    // -------------------------------------------------------------------------
    // Encoding helpers (mirrors ActiveCellFrontierTracker)
    // -------------------------------------------------------------------------

    private static int decodeRow(long index, int gridCols) {
        return (int) (index / gridCols);
    }

    private static int decodeCol(long index, int gridCols) {
        return (int) (index % gridCols);
    }
}