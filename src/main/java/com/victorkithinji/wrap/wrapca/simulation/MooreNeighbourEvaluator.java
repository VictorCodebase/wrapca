package com.victorkithinji.wrap.wrapca.simulation;

import com.victorkithinji.wrap.wrapca.grid.CaGrid;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes the Moore neighbourhood (8 adjacent cells) for a given cell.
 * Returns only in-bounds neighbours, each annotated with its direction index
 * and cell-centre-to-cell-centre distance.
 *
 * Direction index mapping (matches WindProjectionCalculator convention):
 *   0 = N   (-1,  0)
 *   1 = NE  (-1, +1)
 *   2 = E   ( 0, +1)
 *   3 = SE  (+1, +1)
 *   4 = S   (+1,  0)
 *   5 = SW  (+1, -1)
 *   6 = W   ( 0, -1)
 *   7 = NW  (-1, -1)
 *
 * Cardinal distance   = cellSizeMetres
 * Diagonal distance   = cellSizeMetres × √2
 *
 * No Spring annotations — pure utility, used by both the CA engine and the
 * ActiveCellFrontierTracker.
 */
public class MooreNeighbourEvaluator {

    /**
     * Packed [rowOffset, colOffset, directionIndex, isDiagonal(0/1)] for all 8
     * Moore directions in the order defined above.
     * Public so ActiveCellFrontierTracker can reuse the offset pairs.
     */
    public static final int[][] OFFSETS = {
            {-1,  0, 0, 0},   // N
            {-1, +1, 1, 1},   // NE
            { 0, +1, 2, 0},   // E
            {+1, +1, 3, 1},   // SE
            {+1,  0, 4, 0},   // S
            {+1, -1, 5, 1},   // SW
            { 0, -1, 6, 0},   // W
            {-1, -1, 7, 1},   // NW
    };

    private static final double SQRT2 = Math.sqrt(2.0);

    private MooreNeighbourEvaluator() {}

    /**
     * Returns the valid Moore neighbours of cell (row, col) within the grid.
     *
     * @param row  target cell row
     * @param col  target cell column
     * @param grid the CA grid (used for bounds and cell size)
     * @return list of neighbour descriptors; 3–8 entries depending on position
     */
    public static List<NeighbourData> getNeighbours(int row, int col, CaGrid grid) {
        int rows = grid.rows;
        int cols = grid.cols;
        double cellSize = grid.cellSizeMetres;

        List<NeighbourData> result = new ArrayList<>(8);

        for (int[] offset : OFFSETS) {
            int nr = row + offset[0];
            int nc = col + offset[1];

            if (nr < 0 || nr >= rows || nc < 0 || nc >= cols) {
                continue; // out of bounds — skip
            }

            int directionIndex = offset[2];
            boolean isDiagonal  = offset[3] == 1;
            double distance     = isDiagonal ? cellSize * SQRT2 : cellSize;
            long   encodedIndex = ActiveCellFrontierTracker.encode(nr, nc, cols);

            result.add(new NeighbourData(nr, nc, encodedIndex, directionIndex, distance));
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // Value object returned per neighbour
    // -------------------------------------------------------------------------

    /**
     * Descriptor for one Moore neighbour of a target cell.
     */
    public static final class NeighbourData {

        /** Row of this neighbour cell. */
        public final int row;

        /** Column of this neighbour cell. */
        public final int col;

        /** Encoded index: row * gridCols + col. */
        public final long encodedIndex;

        /**
         * Direction index (0–7) from the target cell toward this neighbour.
         * Matches the convention in WindProjectionCalculator.
         */
        public final int directionIndex;

        /**
         * Distance in metres from target cell centre to this neighbour's centre.
         * Cardinal = cellSize, diagonal = cellSize × √2.
         */
        public final double distanceMetres;

        NeighbourData(int row, int col, long encodedIndex,
                      int directionIndex, double distanceMetres) {
            this.row            = row;
            this.col            = col;
            this.encodedIndex   = encodedIndex;
            this.directionIndex = directionIndex;
            this.distanceMetres = distanceMetres;
        }
    }
}