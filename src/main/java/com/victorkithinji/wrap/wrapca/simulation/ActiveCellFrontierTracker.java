package com.victorkithinji.wrap.wrapca.simulation;

import com.victorkithinji.wrap.wrapca.grid.CaGrid;
import com.victorkithinji.wrap.wrapca.grid.CellStateEnum;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tracks the active frontier: the set of UNBURNED cells that have at least one
 * BURNING Moore neighbour. Only frontier cells are evaluated per generation —
 * this is the core efficiency constraint of the CA engine.
 *
 * Lifecycle: one instance per simulation run. Call seedFromGrid() once after
 * grid initialisation (or after a CV state injection), then call the update
 * methods each generation as state transitions occur.
 *
 * Cell coordinates are encoded as: index = row * gridCols + col
 */
@Component
public class ActiveCellFrontierTracker {

    private final Set<Long> frontier = new HashSet<>();

    // -------------------------------------------------------------------------
    // Initialisation
    // -------------------------------------------------------------------------

    /**
     * Scans the full grid and builds the frontier from scratch.
     * Called once at simulation start, and again after any CV state injection
     * that may have changed BURNING cells en masse.
     */
    public void seedFromGrid(CaGrid grid) {
        frontier.clear();
        int rows = grid.rows;
        int cols = grid.cols;
        int[][] states = grid.states;

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (states[r][c] == CellStateEnum.UNBURNED.ordinal()) {
                    if (hasBurningNeighbour(states, r, c, rows, cols)) {
                        frontier.add(encode(r, c, cols));
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Per-generation update
    // -------------------------------------------------------------------------

    /**
     * Called after a generation step with the set of cells that newly ignited.
     *
     * For each newly BURNING cell:
     *   1. Remove it from the frontier (it is no longer UNBURNED).
     *   2. Add all its UNBURNED neighbours to the frontier (they now border fire).
     *
     * @param newlyIgnited  encoded indices of cells that just became BURNING
     * @param grid          current grid state (already updated for this generation)
     */
    public void onIgnition(Set<Long> newlyIgnited, CaGrid grid) {
        int rows = grid.rows;
        int cols = grid.cols;
        int[][] states = grid.states;

        for (long idx : newlyIgnited) {
            int r = decodeRow(idx, cols);
            int c = decodeCol(idx, cols);

            // The cell itself just became BURNING — remove from frontier
            frontier.remove(idx);

            // Its UNBURNED neighbours are now on the frontier
            for (int[] offset : MooreNeighbourEvaluator.OFFSETS) {
                int nr = r + offset[0];
                int nc = c + offset[1];
                if (nr >= 0 && nr < rows && nc >= 0 && nc < cols) {
                    if (states[nr][nc] == CellStateEnum.UNBURNED.ordinal()) {
                        frontier.add(encode(nr, nc, cols));
                    }
                }
            }
        }
    }

    /**
     * Called when cells transition from BURNING → BURNED.
     * Re-evaluates their UNBURNED neighbours: a neighbour should stay on the
     * frontier only if it still has at least one other BURNING neighbour left.
     *
     * @param newlyBurned  encoded indices of cells that just became BURNED
     * @param grid         current grid state (already updated)
     */
    public void onBurnOut(Set<Long> newlyBurned, CaGrid grid) {
        int rows = grid.rows;
        int cols = grid.cols;
        int[][] states = grid.states;

        for (long idx : newlyBurned) {
            int r = decodeRow(idx, cols);
            int c = decodeCol(idx, cols);

            for (int[] offset : MooreNeighbourEvaluator.OFFSETS) {
                int nr = r + offset[0];
                int nc = c + offset[1];
                if (nr >= 0 && nr < rows && nc >= 0 && nc < cols) {
                    if (states[nr][nc] == CellStateEnum.UNBURNED.ordinal()) {
                        if (!hasBurningNeighbour(states, nr, nc, rows, cols)) {
                            frontier.remove(encode(nr, nc, cols));
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Query
    // -------------------------------------------------------------------------

    /** Returns an unmodifiable view of the current frontier. */
    public Set<Long> getFrontier() {
        return Collections.unmodifiableSet(frontier);
    }

    public boolean isEmpty() {
        return frontier.isEmpty();
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private boolean hasBurningNeighbour(int[][] states, int r, int c, int rows, int cols) {
        for (int[] offset : MooreNeighbourEvaluator.OFFSETS) {
            int nr = r + offset[0];
            int nc = c + offset[1];
            if (nr >= 0 && nr < rows && nc >= 0 && nc < cols) {
                if (states[nr][nc] == CellStateEnum.BURNING.ordinal()) {
                    return true;
                }
            }
        }
        return false;
    }

    static long encode(int row, int col, int gridCols) {
        return (long) row * gridCols + col;
    }

    static int decodeRow(long index, int gridCols) {
        return (int) (index / gridCols);
    }

    static int decodeCol(long index, int gridCols) {
        return (int) (index % gridCols);
    }
}