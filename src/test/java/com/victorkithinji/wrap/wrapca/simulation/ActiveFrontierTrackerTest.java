package com.victorkithinji.wrap.wrapca.simulation;

import com.victorkithinji.wrap.wrapca.grid.CaGrid;
import com.victorkithinji.wrap.wrapca.grid.CellStateEnum;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.victorkithinji.wrap.wrapca.simulation.GridTestFactory.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ActiveCellFrontierTracker.
 * <br/>
 * Each test builds a minimal grid, sets up a known BURNING pattern, then
 * asserts on the frontier contents. No Spring context required.
 */
class ActiveCellFrontierTrackerTest {

    // -------------------------------------------------------------------------
    // seedFromGrid
    // -------------------------------------------------------------------------

    @Test
    void seedFromGrid_noFire_frontierIsEmpty() {
        CaGrid grid = unburnedGrid(5, 5, 0.2f);

        ActiveCellFrontierTracker tracker = new ActiveCellFrontierTracker();
        tracker.seedFromGrid(grid);

        assertThat(tracker.getFrontier()).isEmpty();
    }

    @Test
    void seedFromGrid_centerCellBurning_allEightNeighboursOnFrontier() {
        // 5×5 grid, centre cell (2,2) is BURNING
        CaGrid grid = unburnedGrid(5, 5, 0.2f);
        ignite(grid, 2, 2);

        ActiveCellFrontierTracker tracker = new ActiveCellFrontierTracker();
        tracker.seedFromGrid(grid);

        // The 8 Moore neighbours of (2,2): rows 1-3, cols 1-3 minus (2,2) itself
        Set<Long> frontier = tracker.getFrontier();
        assertThat(frontier).hasSize(8);

        int cols = grid.cols;
        for (int r = 1; r <= 3; r++) {
            for (int c = 1; c <= 3; c++) {
                if (r == 2 && c == 2) continue; // the BURNING cell itself
                assertThat(frontier).contains(encode(r, c, cols));
            }
        }
    }

    @Test
    void seedFromGrid_cornerCellBurning_onlyInBoundsNeighboursOnFrontier() {
        // (0,0) burning — only 3 in-bounds neighbours: (0,1), (1,0), (1,1)
        CaGrid grid = unburnedGrid(5, 5, 0.2f);
        ignite(grid, 0, 0);

        ActiveCellFrontierTracker tracker = new ActiveCellFrontierTracker();
        tracker.seedFromGrid(grid);

        assertThat(tracker.getFrontier()).hasSize(3);
    }

    @Test
    void seedFromGrid_burnedCellsNotAddedToFrontier() {
        // (2,2) BURNING, (2,3) BURNED — (2,3) should not appear on the frontier
        CaGrid grid = unburnedGrid(5, 5, 0.2f);
        ignite(grid, 2, 2);
        grid.states[2][3] = CellStateEnum.BURNED.ordinal();

        ActiveCellFrontierTracker tracker = new ActiveCellFrontierTracker();
        tracker.seedFromGrid(grid);

        assertThat(tracker.getFrontier()).doesNotContain(encode(2, 3, grid.cols));
    }

    @Test
    void seedFromGrid_nonCombustibleCellsNotAddedToFrontier() {
        CaGrid grid = unburnedGrid(5, 5, 0.2f);
        ignite(grid, 2, 2);
        grid.states[2][3] = CellStateEnum.NON_COMBUSTIBLE.ordinal();

        ActiveCellFrontierTracker tracker = new ActiveCellFrontierTracker();
        tracker.seedFromGrid(grid);

        assertThat(tracker.getFrontier()).doesNotContain(encode(2, 3, grid.cols));
    }

    // -------------------------------------------------------------------------
    // onIgnition
    // -------------------------------------------------------------------------

    @Test
    void onIgnition_newlyBurningCellRemovedFromFrontier() {
        CaGrid grid = unburnedGrid(5, 5, 0.2f);
        ignite(grid, 2, 2);

        ActiveCellFrontierTracker tracker = new ActiveCellFrontierTracker();
        tracker.seedFromGrid(grid);

        // (2,3) is on the frontier. Simulate it igniting.
        grid.states[2][3] = CellStateEnum.BURNING.ordinal();
        tracker.onIgnition(Set.of(encode(2, 3, grid.cols)), grid);

        assertThat(tracker.getFrontier()).doesNotContain(encode(2, 3, grid.cols));
    }

    @Test
    void onIgnition_newlyBurningCellAddsItsUnburnedNeighboursToFrontier() {
        // 7×7 grid, single fire at (3,3). Frontier = 8 neighbours.
        // (3,4) ignites → its unburned neighbour (3,5) should now be on the frontier.
        CaGrid grid = unburnedGrid(7, 7, 0.2f);
        ignite(grid, 3, 3);

        ActiveCellFrontierTracker tracker = new ActiveCellFrontierTracker();
        tracker.seedFromGrid(grid);

        grid.states[3][4] = CellStateEnum.BURNING.ordinal();
        tracker.onIgnition(Set.of(encode(3, 4, grid.cols)), grid);

        assertThat(tracker.getFrontier()).contains(encode(3, 5, grid.cols));
    }

    // -------------------------------------------------------------------------
    // onBurnOut
    // -------------------------------------------------------------------------

    @Test
    void onBurnOut_neighbourWithNoRemainingBurningNeighboursRemovedFromFrontier() {
        // 5×5, only (2,2) burning. Its neighbour (2,3) is on the frontier.
        // (2,2) burns out → (2,3) has no more BURNING neighbours → removed.
        CaGrid grid = unburnedGrid(5, 5, 0.2f);
        ignite(grid, 2, 2);

        ActiveCellFrontierTracker tracker = new ActiveCellFrontierTracker();
        tracker.seedFromGrid(grid);

        // Simulate burnout: (2,2) → BURNED
        grid.states[2][2] = CellStateEnum.BURNED.ordinal();
        tracker.onBurnOut(Set.of(encode(2, 2, grid.cols)), grid);

        assertThat(tracker.getFrontier()).isEmpty();
    }

    @Test
    void onBurnOut_neighbourWithAnotherBurningNeighbourStaysOnFrontier() {
        // (2,2) and (2,4) both burning. (2,3) is on the frontier of both.
        // (2,2) burns out but (2,4) is still burning → (2,3) should stay.
        CaGrid grid = unburnedGrid(5, 5, 0.2f);
        ignite(grid, 2, 2);
        ignite(grid, 2, 4);

        ActiveCellFrontierTracker tracker = new ActiveCellFrontierTracker();
        tracker.seedFromGrid(grid);

        grid.states[2][2] = CellStateEnum.BURNED.ordinal();
        tracker.onBurnOut(Set.of(encode(2, 2, grid.cols)), grid);

        assertThat(tracker.getFrontier()).contains(encode(2, 3, grid.cols));
    }

    // -------------------------------------------------------------------------
    // isEmpty
    // -------------------------------------------------------------------------

    @Test
    void isEmpty_returnsTrueWhenNoFire() {
        CaGrid grid = unburnedGrid(3, 3, 0.2f);
        ActiveCellFrontierTracker tracker = new ActiveCellFrontierTracker();
        tracker.seedFromGrid(grid);

        assertThat(tracker.isEmpty()).isTrue();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static long encode(int row, int col, int cols) {
        return ActiveCellFrontierTracker.encode(row, col, cols);
    }
}