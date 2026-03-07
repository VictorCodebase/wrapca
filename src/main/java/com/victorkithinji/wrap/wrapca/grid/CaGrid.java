package com.victorkithinji.wrap.wrapca.grid;


/**
 * The complete spatial state of a single CA simulation instance.
 *
 * <p>{@code CaGrid} is a plain Java object with no Spring dependencies so
 * that it can be deep-copied, passed across threads, and unit-tested without
 * a running application context.
 *
 * <h2>Grid coordinates</h2>
 * All 2-D arrays are indexed {@code [row][col]} where row 0 is the
 * northernmost row and column 0 is the westernmost column, matching standard
 * GeoTIFF raster conventions.
 *
 * <h2>Cell encoding</h2>
 * Cell states are stored as {@code int} ordinals of {@link CellState} rather
 * than enum references to avoid object-array overhead on large grids.
 * Read a state with {@code CellState.values()[states[r][c]]} and write it
 * with {@code states[r][c] = CellState.BURNING.ordinal()}.
 *
 * <h2>Cell index encoding</h2>
 * Throughout the engine, single-cell references are encoded as
 * {@code long index = row * cols + col}. Use {@link #encodeIndex(int, int)}
 * and {@link #decodeRow(long)} / {@link #decodeCol(long)} to convert.
 *
 * <h2>Thread safety</h2>
 * This object is <strong>not</strong> thread-safe. The Monte Carlo runner
 * must supply each parallel task with its own deep copy via
 * {@link #deepCopy()}.
 */
public class CaGrid {

    /**
     * Cell state array, indexed {@code [row][col]}.
     * Values are {@link CellState} ordinals — use
     * {@code CellState.values()[states[r][c]]} to obtain the enum constant.
     */
    public final int[][] states;

    /**
     * Per-cell environmental vectors, indexed {@code [row][col]}.
     * Assigned once at grid initialisation; selectively refreshed by CV
     * correction for NDMI values in UNBURNED cells.
     */
    public final CellEnvironment[][] environment;

    /** Number of rows in the grid (north–south extent). */
    public final int rows;

    /** Number of columns in the grid (east–west extent). */
    public final int cols;

    /**
     * Side length of each square cell in metres.
     * Default is 100 m; diagonal cell separation is
     * {@code cellSizeMetres * Math.sqrt(2)}.
     */
    public final double cellSizeMetres;

    /**
     * Constructs a new grid.
     *
     * @param states          pre-populated state ordinal array, dimensions
     *                        must be {@code [rows][cols]}
     * @param environment     pre-populated environment vector array, same
     *                        dimensions
     * @param rows            row count (≥ 1)
     * @param cols            column count (≥ 1)
     * @param cellSizeMetres  cell side length in metres (must be &gt; 0)
     * @throws IllegalArgumentException if any dimension constraint is violated
     */
    public CaGrid(int[][] states, CellEnvironment[][] environment,
                  int rows, int cols, double cellSizeMetres) {
        if (rows < 1 || cols < 1) {
            throw new IllegalArgumentException(
                    "Grid dimensions must be >= 1, got " + rows + "x" + cols);
        }
        if (cellSizeMetres <= 0) {
            throw new IllegalArgumentException(
                    "cellSizeMetres must be > 0, got " + cellSizeMetres);
        }
        if (states.length != rows || states[0].length != cols) {
            throw new IllegalArgumentException(
                    "states array dimensions do not match declared rows/cols");
        }
        if (environment.length != rows || environment[0].length != cols) {
            throw new IllegalArgumentException(
                    "environment array dimensions do not match declared rows/cols");
        }
        this.states = states;
        this.environment = environment;
        this.rows = rows;
        this.cols = cols;
        this.cellSizeMetres = cellSizeMetres;
    }

    // -------------------------------------------------------------------------
    // Cell index helpers
    // -------------------------------------------------------------------------

    /**
     * Encodes a (row, col) pair as a single {@code long} index.
     *
     * <p>Used everywhere a cell must be stored in a {@code Set} or {@code Map}
     * without boxing a two-element array.
     *
     * @param row row coordinate (0-based)
     * @param col column coordinate (0-based)
     * @return encoded index {@code row * cols + col}
     */
    public long encodeIndex(int row, int col) {
        return (long) row * cols + col;
    }

    /**
     * Extracts the row from an encoded cell index.
     *
     * @param index value produced by {@link #encodeIndex(int, int)}
     * @return row coordinate
     */
    public int decodeRow(long index) {
        return (int) (index / cols);
    }

    /**
     * Extracts the column from an encoded cell index.
     *
     * @param index value produced by {@link #encodeIndex(int, int)}
     * @return column coordinate
     */
    public int decodeCol(long index) {
        return (int) (index % cols);
    }

    /**
     * Returns {@code true} if (row, col) is within the grid bounds.
     *
     * @param row row to test
     * @param col column to test
     * @return {@code true} when both coordinates are within [0, rows) and
     *         [0, cols) respectively
     */
    public boolean inBounds(int row, int col) {
        return row >= 0 && row < rows && col >= 0 && col < cols;
    }

    // -------------------------------------------------------------------------
    // State accessors (enum-level, avoids caller boilerplate)
    // -------------------------------------------------------------------------

    /**
     * Returns the {@link CellState} of the cell at (row, col).
     *
     * @param row row coordinate
     * @param col column coordinate
     * @return current cell state
     */
    public CellState getState(int row, int col) {
        return CellState.values()[states[row][col]];
    }

    /**
     * Sets the state of the cell at (row, col).
     *
     * @param row   row coordinate
     * @param col   column coordinate
     * @param state new state
     */
    public void setState(int row, int col, CellState state) {
        states[row][col] = state.ordinal();
    }

    // -------------------------------------------------------------------------
    // Deep copy
    // -------------------------------------------------------------------------

    /**
     * Returns an independent deep copy of this grid.
     *
     * <p>The Monte Carlo ensemble runner calls this once per simulation task
     * so that each parallel run operates on isolated state without
     * synchronisation. {@link CellEnvironment} objects are immutable
     * ({@code @Value}) so the environment array is shallow-copied at the
     * row level — only the state array requires element-wise duplication.
     *
     * @return a new {@code CaGrid} with the same dimensions, cell size, and
     *         a fully independent copy of the states array
     */
    public CaGrid deepCopy() {
        int[][] statesCopy = new int[rows][cols];
        for (int r = 0; r < rows; r++) {
            System.arraycopy(states[r], 0, statesCopy[r], 0, cols);
        }
        // CellEnvironment is @Value (immutable) — row-level array copy suffices
        CellEnvironment[][] envCopy = new CellEnvironment[rows][];
        for (int r = 0; r < rows; r++) {
            envCopy[r] = environment[r].clone();
        }
        return new CaGrid(statesCopy, envCopy, rows, cols, cellSizeMetres);
    }
}
