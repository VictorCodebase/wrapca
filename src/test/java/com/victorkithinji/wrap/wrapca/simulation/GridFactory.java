package com.victorkithinji.wrap.wrapca.simulation;

import com.victorkithinji.wrap.wrapca.grid.CaGrid;
import com.victorkithinji.wrap.wrapca.grid.CellEnvironment;
import com.victorkithinji.wrap.wrapca.grid.CellStateEnum;
import com.victorkithinji.wrap.wrapca.grid.VegetationTypeEnum;
import com.victorkithinji.wrap.wrapca.ingestion.WindField;

/**
 * Test helper. Builds minimal CaGrid and WindField instances without Spring context.
 *
 * All grids use 100m cells, flat terrain, calm wind, and GRASSLAND vegetation
 * unless a test overrides specific cells. Every CellEnvironment is constructed
 * with elevationMetres=0f so slope calculations return 0 (flat) by default.
 */
final class GridFactory {

    static final double CELL_SIZE = 100.0;

    private GridFactory() {}

    /** Uniform UNBURNED grassland grid, flat terrain, no wind. */
    static CaGrid unburnedFlat(int rows, int cols) {
        CellEnvironment[][] env = new CellEnvironment[rows][cols];
        int[][]            states = new int[rows][cols]; // JVM zero-fills → UNBURNED

        CellEnvironment cell = flatGrassland();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                env[r][c] = cell;
            }
        }
        return new CaGrid(states, env, rows, cols, CELL_SIZE);
    }

    /**
     * Places a single BURNING cell at (burnRow, burnCol) in an otherwise
     * UNBURNED flat grassland grid.
     */
    static CaGrid singleBurningCell(int rows, int cols, int burnRow, int burnCol) {
        CaGrid grid = unburnedFlat(rows, cols);
        grid.setState(burnRow, burnCol, CellStateEnum.BURNING);
        return grid;
    }

    /** Calm wind (0 m/s) for a grid of the given dimensions. */
    static WindField calmWind(int rows, int cols) {
        return new WindField(new float[rows][cols], new float[rows][cols], rows, cols);
    }

    /**
     * Uniform southerly wind (from South, blowing North) at the given speed.
     * directionDeg = 180 (meteorological FROM-direction).
     */
    static WindField southerlyWind(int rows, int cols, float speedMs) {
        float[][] speed = new float[rows][cols];
        float[][] dir   = new float[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                speed[r][c] = speedMs;
                dir[r][c]   = 180.0f;
            }
        }
        return new WindField(speed, dir, rows, cols);
    }

    /** Dry grassland cell: ndmi=0.05, flat, elevation 0. Will propagate fire. */
    static CellEnvironment flatGrassland() {
        // ndvi, ndmi, elevationMetres, slopeRadians, aspectRadians, vegetationType
        return new CellEnvironment(0.5f, 0.05f, 0.0f, 0.0f, 0.0f, VegetationTypeEnum.GRASSLAND);
    }

    /** Wet grassland cell: ndmi=0.35, above extinction — will NOT propagate fire. */
    static CellEnvironment wetGrassland() {
        return new CellEnvironment(0.5f, 0.35f, 0.0f, 0.0f, 0.0f, VegetationTypeEnum.GRASSLAND);
    }

    /** Non-combustible cell (water). */
    static CellEnvironment water() {
        return new CellEnvironment(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, VegetationTypeEnum.WATER);
    }
}