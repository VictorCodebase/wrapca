package com.victorkithinji.wrap.wrapca.simulation;

import com.victorkithinji.wrap.wrapca.grid.CaGrid;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static com.victorkithinji.wrap.wrapca.simulation.GridTestFactory.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MooreNeighbourEvaluatorTest {

    private static final double SQRT2 = Math.sqrt(2.0);

    // -------------------------------------------------------------------------
    // Neighbour count based on grid position
    // -------------------------------------------------------------------------

    @Test
    void interiorCell_returns8Neighbours() {
        CaGrid grid = unburnedGrid(5, 5, 0.2f);
        List<MooreNeighbourEvaluator.NeighbourData> neighbours =
                MooreNeighbourEvaluator.getNeighbours(2, 2, grid);

        assertThat(neighbours).hasSize(8);
    }

    @Test
    void cornerCell_returns3Neighbours() {
        CaGrid grid = unburnedGrid(5, 5, 0.2f);
        List<MooreNeighbourEvaluator.NeighbourData> neighbours =
                MooreNeighbourEvaluator.getNeighbours(0, 0, grid);

        assertThat(neighbours).hasSize(3);
    }

    @Test
    void edgeCell_notCorner_returns5Neighbours() {
        CaGrid grid = unburnedGrid(5, 5, 0.2f);
        // Top edge, not corner
        List<MooreNeighbourEvaluator.NeighbourData> neighbours =
                MooreNeighbourEvaluator.getNeighbours(0, 2, grid);

        assertThat(neighbours).hasSize(5);
    }

    @Test
    void singleCellGrid_returnsNoNeighbours() {
        CaGrid grid = unburnedGrid(1, 1, 0.2f);
        List<MooreNeighbourEvaluator.NeighbourData> neighbours =
                MooreNeighbourEvaluator.getNeighbours(0, 0, grid);

        assertThat(neighbours).isEmpty();
    }

    // -------------------------------------------------------------------------
    // All returned neighbours are within grid bounds
    // -------------------------------------------------------------------------

    @Test
    void allNeighboursAreInBounds() {
        CaGrid grid = unburnedGrid(4, 4, 0.2f);

        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                List<MooreNeighbourEvaluator.NeighbourData> nbs =
                        MooreNeighbourEvaluator.getNeighbours(r, c, grid);
                for (MooreNeighbourEvaluator.NeighbourData nb : nbs) {
                    assertThat(nb.row).isBetween(0, 3);
                    assertThat(nb.col).isBetween(0, 3);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Direction indices — 0=N, 1=NE, 2=E, 3=SE, 4=S, 5=SW, 6=W, 7=NW
    // -------------------------------------------------------------------------

    @Test
    void northNeighbourHasDirectionIndex0() {
        CaGrid grid = unburnedGrid(5, 5, 0.2f);
        // Neighbour directly north of (2,2) is (1,2)
        MooreNeighbourEvaluator.NeighbourData north = findNeighbour(
                MooreNeighbourEvaluator.getNeighbours(2, 2, grid), 1, 2);

        assertThat(north).isNotNull();
        assertThat(north.directionIndex).isEqualTo(0);
    }

    @Test
    void southEastNeighbourHasDirectionIndex3() {
        CaGrid grid = unburnedGrid(5, 5, 0.2f);
        MooreNeighbourEvaluator.NeighbourData se = findNeighbour(
                MooreNeighbourEvaluator.getNeighbours(2, 2, grid), 3, 3);

        assertThat(se).isNotNull();
        assertThat(se.directionIndex).isEqualTo(3);
    }

    @Test
    void allEightDirectionIndicesAreUniqueForInteriorCell() {
        CaGrid grid = unburnedGrid(5, 5, 0.2f);
        List<MooreNeighbourEvaluator.NeighbourData> nbs =
                MooreNeighbourEvaluator.getNeighbours(2, 2, grid);

        List<Integer> indices = nbs.stream()
                .map(nb -> nb.directionIndex)
                .collect(Collectors.toList());

        assertThat(indices).doesNotHaveDuplicates();
        assertThat(indices).containsExactlyInAnyOrder(0, 1, 2, 3, 4, 5, 6, 7);
    }

    // -------------------------------------------------------------------------
    // Distances — cardinal vs diagonal
    // -------------------------------------------------------------------------

    @Test
    void cardinalNeighbourDistanceEqualsCellSize() {
        CaGrid grid = unburnedGrid(5, 5, 0.2f);
        // North neighbour (1,2) is cardinal
        MooreNeighbourEvaluator.NeighbourData north = findNeighbour(
                MooreNeighbourEvaluator.getNeighbours(2, 2, grid), 1, 2);

        assertThat(north.distanceMetres).isCloseTo(CELL_SIZE, within(0.001));
    }

    @Test
    void diagonalNeighbourDistanceEqualsCellSizeTimesSqrt2() {
        CaGrid grid = unburnedGrid(5, 5, 0.2f);
        // NE neighbour (1,3) is diagonal
        MooreNeighbourEvaluator.NeighbourData ne = findNeighbour(
                MooreNeighbourEvaluator.getNeighbours(2, 2, grid), 1, 3);

        assertThat(ne.distanceMetres).isCloseTo(CELL_SIZE * SQRT2, within(0.001));
    }

    // -------------------------------------------------------------------------
    // Encoded index consistency
    // -------------------------------------------------------------------------

    @Test
    void encodedIndexMatchesRowTimesColsPluscol() {
        CaGrid grid = unburnedGrid(5, 5, 0.2f);
        int cols = grid.cols;
        List<MooreNeighbourEvaluator.NeighbourData> nbs =
                MooreNeighbourEvaluator.getNeighbours(2, 2, grid);

        for (MooreNeighbourEvaluator.NeighbourData nb : nbs) {
            long expected = (long) nb.row * cols + nb.col;
            assertThat(nb.encodedIndex).isEqualTo(expected);
        }
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private MooreNeighbourEvaluator.NeighbourData findNeighbour(
            List<MooreNeighbourEvaluator.NeighbourData> nbs, int row, int col) {
        return nbs.stream()
                .filter(nb -> nb.row == row && nb.col == col)
                .findFirst()
                .orElse(null);
    }
}