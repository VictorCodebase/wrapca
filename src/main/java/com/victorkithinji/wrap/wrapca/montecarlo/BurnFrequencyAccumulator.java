package com.victorkithinji.wrap.wrapca.montecarlo;

import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Thread-safe accumulator for burn counts across N parallel Monte Carlo runs.
 *
 * <p>Backed by an {@link AtomicIntegerArray} sized {@code rows × cols} (row-major).
 * Each Monte Carlo task calls {@link #record(int, int)} for every cell that was
 * BURNED or BURNING at the end of its run.  Concurrent increments are safe —
 * no external synchronisation is needed.
 *
 * <p>Not a Spring bean — instantiated directly by {@link MonteCarloEnsembleRunner}
 * once per ensemble run and discarded after {@link RiskMapAssembler} has read it.
 */
public class BurnFrequencyAccumulator {

    private final AtomicIntegerArray counts;
    private final int rows;
    private final int cols;

    public BurnFrequencyAccumulator(int rows, int cols) {
        if (rows < 1 || cols < 1) {
            throw new IllegalArgumentException("rows and cols must be ≥ 1");
        }
        this.rows   = rows;
        this.cols   = cols;
        this.counts = new AtomicIntegerArray(rows * cols);
    }

    /**
     * Increments the burn count for the cell at {@code (row, col)} by 1.
     * Safe to call from any thread concurrently.
     */
    public void record(int row, int col) {
        counts.incrementAndGet(row * cols + col);
    }

    /**
     * Increments the burn count for the encoded cell index by 1.
     * Safe to call from any thread concurrently.
     */
    public void record(long encodedIndex) {
        counts.incrementAndGet((int) encodedIndex);
    }

    /**
     * Returns the burn count for the cell at {@code (row, col)}.
     */
    public int getCount(int row, int col) {
        return counts.get(row * cols + col);
    }

    /**
     * Returns the burn count for the encoded cell index.
     */
    public int getCount(long encodedIndex) {
        return counts.get((int) encodedIndex);
    }

    public int getRows() { return rows; }
    public int getCols() { return cols; }

    /**
     * Returns a snapshot of the raw counts as a plain {@code int[][]}, row-major.
     * Allocates a new array — intended for {@link RiskMapAssembler} to read once
     * after all tasks have completed.
     */
    public int[][] snapshot() {
        int[][] snap = new int[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                snap[r][c] = counts.get(r * cols + c);
            }
        }
        return snap;
    }
}