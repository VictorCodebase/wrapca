package com.victorkithinji.wrap.wrapca.montecarlo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class RiskMapAssemblerTest {

    private RiskMapAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new RiskMapAssembler();
    }

    @Test
    void damagePotentialIsCountDividedByTotalRuns() {
        BurnFrequencyAccumulator acc = new BurnFrequencyAccumulator(2, 2);
        acc.record(0, 0); // burned in 1 run
        acc.record(0, 0); // burned in 2 runs
        acc.record(1, 1); // burned in 1 run

        float[] ic = {0.5f, 0.5f, 0.5f, 0.5f};
        PhaseOneResult result = assembler.assemble(acc, ic, 4);

        // cell (0,0) = index 0: count 2 / 4 = 0.5
        assertThat(result.getDamagePotential()[0]).isCloseTo(0.5f, within(1e-6f));
        // cell (1,1) = index 3: count 1 / 4 = 0.25
        assertThat(result.getDamagePotential()[3]).isCloseTo(0.25f, within(1e-6f));
        // unburned cell (0,1) = index 1: count 0 / 4 = 0.0
        assertThat(result.getDamagePotential()[1]).isEqualTo(0f);
    }

    @Test
    void ignitionLikelihoodNormalisedSoPeakIsOne() {
        BurnFrequencyAccumulator acc = new BurnFrequencyAccumulator(1, 3);
        float[] ic = {0.2f, 0.8f, 0.4f};
        PhaseOneResult result = assembler.assemble(acc, ic, 1);

        float peak = 0f;
        for (float v : result.getIgnitionLikelihood()) if (v > peak) peak = v;
        assertThat(peak).isCloseTo(1.0f, within(1e-6f));
    }

    @Test
    void ignitionLikelihoodRetainsRelativeOrdering() {
        BurnFrequencyAccumulator acc = new BurnFrequencyAccumulator(1, 3);
        float[] ic = {0.1f, 0.6f, 0.3f};
        PhaseOneResult result = assembler.assemble(acc, ic, 1);

        float[] il = result.getIgnitionLikelihood();
        assertThat(il[1]).isGreaterThan(il[2]);
        assertThat(il[2]).isGreaterThan(il[0]);
    }

    @Test
    void allZeroIcProducesAllZeroIgnitionLikelihood() {
        BurnFrequencyAccumulator acc = new BurnFrequencyAccumulator(2, 2);
        float[] ic = {0f, 0f, 0f, 0f};
        PhaseOneResult result = assembler.assemble(acc, ic, 10);

        for (float v : result.getIgnitionLikelihood()) {
            assertThat(v).isEqualTo(0f);
        }
    }

    @Test
    void allCellsBurnedProducesDamagePotentialOfOne() {
        int runs = 10;
        BurnFrequencyAccumulator acc = new BurnFrequencyAccumulator(1, 2);
        for (int i = 0; i < runs; i++) {
            acc.record(0, 0);
            acc.record(0, 1);
        }
        float[] ic = {0.5f, 0.5f};
        PhaseOneResult result = assembler.assemble(acc, ic, runs);

        assertThat(result.getDamagePotential()[0]).isCloseTo(1.0f, within(1e-6f));
        assertThat(result.getDamagePotential()[1]).isCloseTo(1.0f, within(1e-6f));
    }

    @Test
    void outputDimensionsMatchAccumulator() {
        BurnFrequencyAccumulator acc = new BurnFrequencyAccumulator(5, 7);
        float[] ic = new float[35];
        for (int i = 0; i < 35; i++) ic[i] = 0.5f;
        PhaseOneResult result = assembler.assemble(acc, ic, 1);

        assertThat(result.getRows()).isEqualTo(5);
        assertThat(result.getCols()).isEqualTo(7);
        assertThat(result.getDamagePotential()).hasSize(35);
        assertThat(result.getIgnitionLikelihood()).hasSize(35);
    }

    @Test
    void throwsWhenIcLengthMismatch() {
        BurnFrequencyAccumulator acc = new BurnFrequencyAccumulator(2, 2);
        float[] ic = new float[3]; // should be 4
        assertThatThrownBy(() -> assembler.assemble(acc, ic, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void throwsWhenTotalRunsIsZero() {
        BurnFrequencyAccumulator acc = new BurnFrequencyAccumulator(2, 2);
        float[] ic = new float[4];
        assertThatThrownBy(() -> assembler.assemble(acc, ic, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}