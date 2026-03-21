package com.victorkithinji.wrap.wrapca.simulation;

import com.victorkithinji.wrap.wrapca.grid.CaGrid;
import com.victorkithinji.wrap.wrapca.grid.CellEnvironment;
import com.victorkithinji.wrap.wrapca.grid.CellStateEnum;
import com.victorkithinji.wrap.wrapca.grid.VegetationTypeEnum;
import com.victorkithinji.wrap.wrapca.ingestion.WindField;

/**
 * Shared factory for building minimal CaGrid and WindField instances in tests.
 * Keeps test body code short and focused on the assertion being made.
 */
class GridTestFactory {

    static final double CELL_SIZE = 100.0;

    /**
     * Builds a rows × cols grid where every cell is UNBURNED GRASSLAND with
     * the supplied ndmi. All slope / aspect values are zero (flat, no aspect).
     */
    static CaGrid unburnedGridVegetation(int rows, int cols, float ndmi, VegetationTypeEnum vegetationType) {
        CellEnvironment[][] env = new CellEnvironment[rows][cols];
        int[][] states = new int[rows][cols]; // zero-init = UNBURNED

        CellEnvironment cell = new CellEnvironment(
                0.5f,                       // ndvi
                ndmi,                       // ndmi (moisture proxy)
                0.0f,                       // slopeRadians
                0.0f,                       // aspectRadians
                vegetationType
        );

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                env[r][c] = cell;
            }
        }

        return new CaGrid(states, env, rows, cols, CELL_SIZE);
    }

    static CaGrid unburnedGrid(int rows, int cols, float ndmi){
        VegetationTypeEnum vegetation = VegetationTypeEnum.GRASSLAND;
        return  unburnedGridVegetation(rows, cols, ndmi, vegetation);
    };

    /**
     * Sets a single cell to BURNING. Mutates the states array directly —
     * consistent with how CaSpreadEngine writes state.
     */
    static void ignite(CaGrid grid, int row, int col) {
        grid.states[row][col] = CellStateEnum.BURNING.ordinal();
    }

    /**
     * Calm-conditions wind field: zero speed, north direction throughout.
     */
    static WindField calmWind(int rows, int cols) {
        float[][] speed = new float[rows][cols];     // zero
        float[][] dir   = new float[rows][cols];     // 0° = from north
        return new WindField(speed, dir, rows, cols);
    }

    /**
     * Uniform wind field: same speed and direction for every cell.
     */
    static WindField uniformWind(int rows, int cols, float speedMs, float dirDeg) {
        float[][] speed = new float[rows][cols];
        float[][] dir   = new float[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                speed[r][c] = speedMs;
                dir[r][c]   = dirDeg;
            }
        }
        return new WindField(speed, dir, rows, cols);
    }

    /** Counts cells in the given state across the whole grid. */
    static int countCellsInState(CaGrid grid, CellStateEnum state) {
        int[][] states = grid.states;
        int count = 0;
        for (int r = 0; r < grid.rows; r++) {
            for (int c = 0; c < grid.cols; c++) {
                if (states[r][c] == state.ordinal()) count++;
            }
        }
        return count;
    }

    /**
     * Counts BURNED or BURNING cells within a column band (inclusive).
     * Used to assert directional spread asymmetry east/west.
     */
    static int countBurnedInColumns(CaGrid grid, int fromCol, int toCol){
        int count = 0;

        for (int r=0; r<grid.rows; r++){
            for (int c = fromCol; c <= toCol; c++ ){
                CellStateEnum s = grid.getState(r,c);
                if(s==CellStateEnum.BURNED || s==CellStateEnum.BURNING) count++;
            }
        }
        return  count;
    }

    /**
     * Counts BURNED or BURNING cells within a row band (inclusive).
     * Used to assert directional spread asymmetry north/south.
     */
    static int countBurnedInRows(CaGrid grid, int fromRow, int toRow){
        int count = 0;

        for (int r = fromRow; r <= toRow; r++){
            for (int c = 0; c < grid.cols; c++){
                CellStateEnum s = grid.getState(r,c);
                if (s == CellStateEnum.BURNING || s == CellStateEnum.BURNED) count++;
            }
        }
        return count;
    }

    /** Returns true if every cell in the grid is BURNED or BURNING. */
    static boolean isFullyBurned(CaGrid grid) {
        for (int r = 0; r < grid.rows; r++) {
            for (int c = 0; c < grid.cols; c++) {
                CellStateEnum s = grid.getState(r, c);
                if (s != CellStateEnum.BURNED && s != CellStateEnum.BURNING) return false;
            }
        }
        return true;
    }
}