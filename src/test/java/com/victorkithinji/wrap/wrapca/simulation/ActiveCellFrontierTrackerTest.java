package com.victorkithinji.wrap.wrapca.simulation;

import com.victorkithinji.wrap.wrapca.grid.CaGrid;
import com.victorkithinji.wrap.wrapca.grid.CellStateEnum;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ActiveCellFrontierTrackerTest {

    // -------------------------------------------------------------------------
    // seedFromGrid
    // -------------------------------------------------------------------------

    @Test
    void seedFromGrid_emptyWhenNoFire() {
        CaGrid grid = GridFactory.unburnedFlat(5, 5);
        ActiveCellFrontierTracker tracker = new ActiveCellFrontierTracker();
        tracker.seedFromGrid(grid);

        assertThat(tracker.isEmpty()).isTrue();
    }

    @Test
    void seedFromGrid_capturesUnburnedNeighboursOfBurningCell() {
        // 3×3 grid, centre cell BURNING — all 8 neighbours should be in frontier
        CaGrid grid = GridFactory.singleBurningCell(3, 3, 1, 1);
        ActiveCellFrontierTracker tracker = new ActiveCellFrontierTracker();
        tracker.seedFromGrid(grid);

        assertThat(tracker.getFrontier()).hasSize(8);
        assertThat(tracker.getFrontier()).doesNotContain(grid.encodeIndex(1, 1));
    }

    @Test
    void seedFromGrid_doesNotIncludeBurningCellItself() {
        CaGrid grid = GridFactory.singleBurningCell(5, 5, 2, 2);
        ActiveCellFrontierTracker tracker = new ActiveCellFrontierTracker();
        tracker.seedFromGrid(grid);

        assertThat(tracker.getFrontier()).doesNotContain(grid.encodeIndex(2, 2));
    }

    @Test
    void seedFromGrid_doesNotIncludeNonCombustibleNeighbours() {
        // 3×3, centre BURNING, top-left corner NON_COMBUSTIBLE
        CaGrid grid = GridFactory.singleBurningCell(3, 3, 1, 1);
        grid.setState(0, 0, CellStateEnum.NON_COMBUSTIBLE);

        ActiveCellFrontierTracker tracker = new ActiveCellFrontierTracker();
        tracker.seedFromGrid(grid);

        assertThat(tracker.getFrontier()).doesNotContain(grid.encodeIndex(0, 0));
        assertThat(tracker.getFrontier()).hasSize(7); // 8 neighbours − 1 non-combustible
    }

    @Test
    void seedFromGrid_cornerBurningCellYieldsThreeFrontierCells() {
        CaGrid grid = GridFactory.singleBurningCell(3, 3, 0, 0);
        ActiveCellFrontierTracker tracker = new ActiveCellFrontierTracker();
        tracker.seedFromGrid(grid);

        assertThat(tracker.getFrontier()).hasSize(3);
    }

    // -------------------------------------------------------------------------
    // onIgnition
    // -------------------------------------------------------------------------

    @Test
    void onIgnition_removesNowBurningCellFromFrontier() {
        // 5×5, centre (2,2) BURNING. Seed. Then ignite (2,3).
        CaGrid grid = GridFactory.singleBurningCell(5, 5, 2, 2);
        ActiveCellFrontierTracker tracker = new ActiveCellFrontierTracker();
        tracker.seedFromGrid(grid);

        long targetIdx = grid.encodeIndex(2, 3);
        assertThat(tracker.getFrontier()).contains(targetIdx);

        // Simulate the state transition, then notify tracker
        grid.setState(2, 3, CellStateEnum.BURNING);
        tracker.onIgnition(Set.of(targetIdx), grid);

        assertThat(tracker.getFrontier()).doesNotContain(targetIdx);
    }

    @Test
    void onIgnition_addsUnburnedNeighboursOfNewlyBurningCell() {
        // 5×5 grid. Initially only (2,2) is BURNING.
        CaGrid grid = GridFactory.singleBurningCell(5, 5, 2, 2);
        ActiveCellFrontierTracker tracker = new ActiveCellFrontierTracker();
        tracker.seedFromGrid(grid);

        // (2,4) is not adjacent to (2,2) so not in frontier yet
        assertThat(tracker.getFrontier()).doesNotContain(grid.encodeIndex(2, 4));

        // Ignite (2,3)
        grid.setState(2, 3, CellStateEnum.BURNING);
        tracker.onIgnition(Set.of(grid.encodeIndex(2, 3)), grid);

        // (2,4) is now adjacent to BURNING (2,3) → must be in frontier
        assertThat(tracker.getFrontier()).contains(grid.encodeIndex(2, 4));
    }

    // -------------------------------------------------------------------------
    // onBurnOut
    // -------------------------------------------------------------------------

    @Test
    void onBurnOut_removesFrontierCellWhenItLosesAllBurningNeighbours() {
        // 1×3 grid: [UNBURNED, BURNING, UNBURNED]
        // (0,0) is in frontier because (0,1) is BURNING.
        // After (0,1) burns out, (0,0) should leave the frontier.
        CaGrid grid = GridFactory.unburnedFlat(1, 3);
        grid.setState(0, 1, CellStateEnum.BURNING);

        ActiveCellFrontierTracker tracker = new ActiveCellFrontierTracker();
        tracker.seedFromGrid(grid);

        assertThat(tracker.getFrontier()).contains(grid.encodeIndex(0, 0));
        assertThat(tracker.getFrontier()).contains(grid.encodeIndex(0, 2));

        // (0,1) burns out
        grid.setState(0, 1, CellStateEnum.BURNED);
        tracker.onBurnOut(Set.of(grid.encodeIndex(0, 1)), grid);

        assertThat(tracker.getFrontier()).doesNotContain(grid.encodeIndex(0, 0));
        assertThat(tracker.getFrontier()).doesNotContain(grid.encodeIndex(0, 2));
        assertThat(tracker.isEmpty()).isTrue();
    }

    @Test
    void onBurnOut_keepsNeighbourInFrontierIfAnotherBurningNeighbourRemains() {
        // 3×3: (1,1) and (1,2) both BURNING. (1,0) is in frontier due to (1,1).
        // When (1,1) burns out, (1,0) should stay because (1,2) is still BURNING.
        CaGrid grid = GridFactory.unburnedFlat(3, 3);
        grid.setState(1, 1, CellStateEnum.BURNING);
        grid.setState(1, 2, CellStateEnum.BURNING);

        ActiveCellFrontierTracker tracker = new ActiveCellFrontierTracker();
        tracker.seedFromGrid(grid);

        long cell10 = grid.encodeIndex(1, 0);
        assertThat(tracker.getFrontier()).contains(cell10);

        // Burn out (1,1); (1,2) still BURNING
        grid.setState(1, 1, CellStateEnum.BURNED);
        tracker.onBurnOut(Set.of(grid.encodeIndex(1, 1)), grid);

        assertThat(tracker.getFrontier()).contains(cell10);
    }

    // -------------------------------------------------------------------------
    // getFrontier is unmodifiable
    // -------------------------------------------------------------------------

    @Test
    void getFrontier_returnsUnmodifiableView() {
        CaGrid grid = GridFactory.singleBurningCell(3, 3, 1, 1);
        ActiveCellFrontierTracker tracker = new ActiveCellFrontierTracker();
        tracker.seedFromGrid(grid);

        Set<Long> view = tracker.getFrontier();
        assertThat(view).isNotEmpty();
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> view.add(999L));
    }
}