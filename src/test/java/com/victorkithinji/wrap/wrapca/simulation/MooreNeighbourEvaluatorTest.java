package com.victorkithinji.wrap.wrapca.simulation;

import com.victorkithinji.wrap.wrapca.grid.CaGrid;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class MooreNeighbourEvaluatorTest {

    // -------------------------------------------------------------------------
    // Neighbour counts by position
    // -------------------------------------------------------------------------

    @Test
    void interiorCellReturnsEightNeighbours() {
        CaGrid grid = GridFactory.unburnedFlat(5, 5);
        List<NeighbourData> neighbours = MooreNeighbourEvaluator.getNeighbours(2, 2, grid);
        assertThat(neighbours).hasSize(8);
    }

    @Test
    void cornerCellReturnsThreeNeighbours() {
        CaGrid grid = GridFactory.unburnedFlat(5, 5);
        assertThat(MooreNeighbourEvaluator.getNeighbours(0, 0, grid)).hasSize(3);
        assertThat(MooreNeighbourEvaluator.getNeighbours(0, 4, grid)).hasSize(3);
        assertThat(MooreNeighbourEvaluator.getNeighbours(4, 0, grid)).hasSize(3);
        assertThat(MooreNeighbourEvaluator.getNeighbours(4, 4, grid)).hasSize(3);
    }

    @Test
    void edgeCellReturnsFiveNeighbours() {
        CaGrid grid = GridFactory.unburnedFlat(5, 5);
        // top edge, not corner
        assertThat(MooreNeighbourEvaluator.getNeighbours(0, 2, grid)).hasSize(5);
        // left edge, not corner
        assertThat(MooreNeighbourEvaluator.getNeighbours(2, 0, grid)).hasSize(5);
    }

    @Test
    void singleCellGridReturnsNoNeighbours() {
        CaGrid grid = GridFactory.unburnedFlat(1, 1);
        assertThat(MooreNeighbourEvaluator.getNeighbours(0, 0, grid)).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Direction indices and offsets
    // -------------------------------------------------------------------------

    @Test
    void directionIndicesAreZeroToSeven() {
        CaGrid grid = GridFactory.unburnedFlat(3, 3);
        List<NeighbourData> neighbours = MooreNeighbourEvaluator.getNeighbours(1, 1, grid);

        Set<Integer> dirs = neighbours.stream()
                .map(NeighbourData::getDirectionIndex)
                .collect(Collectors.toSet());

        assertThat(dirs).containsExactlyInAnyOrder(0, 1, 2, 3, 4, 5, 6, 7);
    }

    @Test
    void northNeighbourHasDirectionIndexZero() {
        CaGrid grid = GridFactory.unburnedFlat(3, 3);
        // Centre cell (1,1). North neighbour is (0,1).
        List<NeighbourData> neighbours = MooreNeighbourEvaluator.getNeighbours(1, 1, grid);

        NeighbourData north = neighbours.stream()
                .filter(nb -> nb.getRow() == 0 && nb.getCol() == 1)
                .findFirst()
                .orElseThrow();

        assertThat(north.getDirectionIndex()).isEqualTo(0);
    }

    @Test
    void southNeighbourHasDirectionIndexFour() {
        CaGrid grid = GridFactory.unburnedFlat(3, 3);
        List<NeighbourData> neighbours = MooreNeighbourEvaluator.getNeighbours(1, 1, grid);

        NeighbourData south = neighbours.stream()
                .filter(nb -> nb.getRow() == 2 && nb.getCol() == 1)
                .findFirst()
                .orElseThrow();

        assertThat(south.getDirectionIndex()).isEqualTo(4);
    }

    // -------------------------------------------------------------------------
    // Distances
    // -------------------------------------------------------------------------

    @Test
    void cardinalNeighboursHaveCellSizeDistance() {
        CaGrid grid = GridFactory.unburnedFlat(3, 3);
        List<NeighbourData> neighbours = MooreNeighbourEvaluator.getNeighbours(1, 1, grid);

        neighbours.stream()
                .filter(nb -> nb.getDirectionIndex() % 2 == 0) // cardinal: 0, 2, 4, 6
                .forEach(nb -> assertThat(nb.getDistanceMetres())
                        .isCloseTo(GridFactory.CELL_SIZE, org.assertj.core.data.Offset.offset(0.001)));
    }

    @Test
    void diagonalNeighboursHaveSqrt2Distance() {
        CaGrid grid = GridFactory.unburnedFlat(3, 3);
        List<NeighbourData> neighbours = MooreNeighbourEvaluator.getNeighbours(1, 1, grid);

        double expected = GridFactory.CELL_SIZE * Math.sqrt(2.0);
        neighbours.stream()
                .filter(nb -> nb.getDirectionIndex() % 2 == 1) // diagonal: 1, 3, 5, 7
                .forEach(nb -> assertThat(nb.getDistanceMetres())
                        .isCloseTo(expected, org.assertj.core.data.Offset.offset(0.001)));
    }

    // -------------------------------------------------------------------------
    // Encoded indices
    // -------------------------------------------------------------------------

    @Test
    void encodedIndexMatchesRowTimesColsPlusCol() {
        CaGrid grid = GridFactory.unburnedFlat(5, 7);
        List<NeighbourData> neighbours = MooreNeighbourEvaluator.getNeighbours(2, 3, grid);

        for (NeighbourData nb : neighbours) {
            long expected = (long) nb.getRow() * 7 + nb.getCol();
            assertThat(nb.getEncodedIndex()).isEqualTo(expected);
        }
    }

    // -------------------------------------------------------------------------
    // All neighbours are in bounds
    // -------------------------------------------------------------------------

    @Test
    void allReturnedNeighboursAreInBounds() {
        CaGrid grid = GridFactory.unburnedFlat(4, 4);
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                List<NeighbourData> neighbours = MooreNeighbourEvaluator.getNeighbours(r, c, grid);
                for (NeighbourData nb : neighbours) {
                    assertThat(grid.inBounds(nb.getRow(), nb.getCol()))
                            .as("Neighbour (%d,%d) must be in bounds", nb.getRow(), nb.getCol())
                            .isTrue();
                }
            }
        }
    }
}