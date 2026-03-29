package com.victorkithinji.wrap.wrapca.montecarlo;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class BurnFrequencyAccumulatorTest {

    @Test
    void initialCountsAreZero() {
        BurnFrequencyAccumulator acc = new BurnFrequencyAccumulator(3, 4);
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 4; c++)
                assertThat(acc.getCount(r, c)).isZero();
    }

    @Test
    void recordByRowColIncrementsCorrectCell() {
        BurnFrequencyAccumulator acc = new BurnFrequencyAccumulator(3, 3);
        acc.record(1, 2);
        assertThat(acc.getCount(1, 2)).isEqualTo(1);
        assertThat(acc.getCount(0, 0)).isZero();
    }

    @Test
    void recordByEncodedIndexIncrementsCorrectCell() {
        BurnFrequencyAccumulator acc = new BurnFrequencyAccumulator(3, 3);
        // encoded index for (2, 1) = 2*3 + 1 = 7
        acc.record(7L);
        assertThat(acc.getCount(2, 1)).isEqualTo(1);
    }

    @Test
    void multipleRecordsAccumulate() {
        BurnFrequencyAccumulator acc = new BurnFrequencyAccumulator(2, 2);
        for (int i = 0; i < 10; i++) acc.record(0, 0);
        assertThat(acc.getCount(0, 0)).isEqualTo(10);
    }

    @Test
    void snapshotMatchesAtomicValues() {
        BurnFrequencyAccumulator acc = new BurnFrequencyAccumulator(2, 3);
        acc.record(0, 0);
        acc.record(0, 0);
        acc.record(1, 2);

        int[][] snap = acc.snapshot();
        assertThat(snap[0][0]).isEqualTo(2);
        assertThat(snap[1][2]).isEqualTo(1);
        assertThat(snap[0][1]).isZero();
    }

    @Test
    void concurrentRecordsAreThreadSafe() throws InterruptedException {
        int rows = 10, cols = 10;
        BurnFrequencyAccumulator acc = new BurnFrequencyAccumulator(rows, cols);
        int threads = 8;
        int recordsPerThread = 10_000;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                for (int i = 0; i < recordsPerThread; i++) {
                    acc.record(0, 0);
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(acc.getCount(0, 0)).isEqualTo(threads * recordsPerThread);
    }

    @Test
    void getDimensionsMatchConstructor() {
        BurnFrequencyAccumulator acc = new BurnFrequencyAccumulator(7, 11);
        assertThat(acc.getRows()).isEqualTo(7);
        assertThat(acc.getCols()).isEqualTo(11);
    }

    @Test
    void throwsOnInvalidDimensions() {
        assertThatThrownBy(() -> new BurnFrequencyAccumulator(0, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BurnFrequencyAccumulator(5, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}