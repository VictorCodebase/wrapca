package com.victorkithinji.wrap.wrapca.simulation;

import com.victorkithinji.wrap.wrapca.grid.CaGrid;

import java.util.ArrayList;
import java.util.List;

/**
 * Static utility. Computes the in-bounds Moore neighbours of a cell.
 *
 * Direction index convention (clockwise from N):
 *   0=N  1=NE  2=E  3=SE  4=S  5=SW  6=W  7=NW
 *
 * Matches WindProjectionCalculator.effectiveComponent() directionIndex exactly.
 * Always pass the direction FROM the source cell TOWARD the target when projecting wind.
 */
public final class MooreNeighbourEvaluator {

    // [directionIndex][0]=rowOffset, [directionIndex][1]=colOffset
    private static final int[][] OFFSETS = {
            {-1,  0},  // 0 N
            {-1, +1},  // 1 NE
            { 0, +1},  // 2 E
            {+1, +1},  // 3 SE
            {+1,  0},  // 4 S
            {+1, -1},  // 5 SW
            { 0, -1},  // 6 W
            {-1, -1},  // 7 NW
    };

    private MooreNeighbourEvaluator() {}

    /**
     * Returns all in-bounds Moore neighbours of (row, col).
     * Interior cells → 8 results. Edge → 5. Corner → 3. 1×1 grid → 0.
     */
    public static List<NeighbourData> getNeighbours(int row, int col, CaGrid grid) {
        double cellSize = grid.cellSizeMetres;
        double diagonal = cellSize * Math.sqrt(2.0);
        int    cols     = grid.cols;

        List<NeighbourData> result = new ArrayList<>(8);

        for (int dir = 0; dir < 8; dir++) {
            int nr = row + OFFSETS[dir][0];
            int nc = col + OFFSETS[dir][1];

            if (!grid.inBounds(nr, nc)) continue;

            boolean isDiagonal = (dir % 2 == 1);
            double  distance   = isDiagonal ? diagonal : cellSize;
            long    encoded    = (long) nr * cols + nc;

            result.add(new NeighbourData(nr, nc, encoded, dir, distance));
        }

        return result;
    }
}