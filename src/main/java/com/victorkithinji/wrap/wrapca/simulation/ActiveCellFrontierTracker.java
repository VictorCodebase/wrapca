package com.victorkithinji.wrap.wrapca.simulation;

import com.victorkithinji.wrap.wrapca.grid.CaGrid;
import com.victorkithinji.wrap.wrapca.grid.CellStateEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Maintains the set of UNBURNED cells that have at least one BURNING Moore neighbour.
 * CaSpreadEngine iterates this set each generation — cells outside the frontier
 * are never evaluated.
 *
 * Scoped as prototype so each CaSpreadEngine.run() gets a fresh instance.
 * Consumers should not hold references to this class directly.
 */
@Slf4j
@Component
@Scope("prototype")
public class ActiveCellFrontierTracker {

    private final Set<Long> frontier = new HashSet<>();

    /**
     * Builds the frontier from scratch by scanning the full grid.
     * Call once before the first generation, and after bulk CV state injection.
     */
    public void seedFromGrid(CaGrid grid) {
        frontier.clear();
        int rows = grid.rows;
        int cols = grid.cols;

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (grid.getState(r, c) != CellStateEnum.UNBURNED) continue;
                if (hasAnyBurningNeighbour(r, c, grid)) {
                    frontier.add(grid.encodeIndex(r, c));
                }
            }
        }
        log.debug("Frontier seeded: {} cells", frontier.size());
    }

    /**
     * Called after UNBURNED → BURNING transitions are applied to the grid.
     * Removes each newly burning cell from the frontier and adds its UNBURNED neighbours.
     * Grid must already reflect the new BURNING states before this is called.
     */
    public void onIgnition(Set<Long> newlyIgnited, CaGrid grid) {
        for (long idx : newlyIgnited) {
            frontier.remove(idx);
            int r = grid.decodeRow(idx);
            int c = grid.decodeCol(idx);
            List<NeighbourData> neighbours = MooreNeighbourEvaluator.getNeighbours(r, c, grid);
            for (NeighbourData nb : neighbours) {
                if (grid.getState(nb.getRow(), nb.getCol()) == CellStateEnum.UNBURNED) {
                    frontier.add(nb.getEncodedIndex());
                }
            }
        }
    }

    /**
     * Called after BURNING → BURNED transitions are applied.
     * Re-evaluates burned cells' UNBURNED neighbours — removes from frontier if no
     * BURNING neighbour remains.
     */
    public void onBurnOut(Set<Long> newlyBurned, CaGrid grid) {
        for (long idx : newlyBurned) {
            int r = grid.decodeRow(idx);
            int c = grid.decodeCol(idx);
            List<NeighbourData> neighbours = MooreNeighbourEvaluator.getNeighbours(r, c, grid);
            for (NeighbourData nb : neighbours) {
                if (grid.getState(nb.getRow(), nb.getCol()) != CellStateEnum.UNBURNED) continue;
                if (!hasAnyBurningNeighbour(nb.getRow(), nb.getCol(), grid)) {
                    frontier.remove(nb.getEncodedIndex());
                }
            }
        }
    }

    /** Returns an unmodifiable view of the current frontier. */
    public Set<Long> getFrontier() {
        return Collections.unmodifiableSet(frontier);
    }

    public boolean isEmpty() {
        return frontier.isEmpty();
    }

    // -------------------------------------------------------------------------

    private boolean hasAnyBurningNeighbour(int row, int col, CaGrid grid) {
        List<NeighbourData> neighbours = MooreNeighbourEvaluator.getNeighbours(row, col, grid);
        for (NeighbourData nb : neighbours) {
            if (grid.getState(nb.getRow(), nb.getCol()) == CellStateEnum.BURNING) return true;
        }
        return false;
    }
}