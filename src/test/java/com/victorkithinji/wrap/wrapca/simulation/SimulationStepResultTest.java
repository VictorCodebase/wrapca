package com.victorkithinji.wrap.wrapca.simulation;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SimulationStepResultTest {

    @Test
    void storesAllFieldsCorrectly() {
        Set<Long> cells = Set.of(1L, 2L, 3L);
        Instant   ts    = Instant.now();

        SimulationStepResult result = new SimulationStepResult(new HashSet<>(cells), 7, ts);

        assertThat(result.getNewlyIgnitedCells()).containsExactlyInAnyOrderElementsOf(cells);
        assertThat(result.getGeneration()).isEqualTo(7);
        assertThat(result.getTimestamp()).isEqualTo(ts);
    }

    @Test
    void newlyIgnitedCellsIsUnmodifiable() {
        SimulationStepResult result =
                new SimulationStepResult(new HashSet<>(Set.of(1L)), 0, Instant.now());

        assertThrows(UnsupportedOperationException.class,
                () -> result.getNewlyIgnitedCells().add(99L));
    }

    @Test
    void emptyIgnitedSetIsAccepted() {
        SimulationStepResult result =
                new SimulationStepResult(new HashSet<>(), 0, Instant.now());

        assertThat(result.getNewlyIgnitedCells()).isEmpty();
    }

    @Test
    void mutatingSourceSetDoesNotAffectResult() {
        HashSet<Long> source = new HashSet<>(Set.of(10L, 20L));
        SimulationStepResult result = new SimulationStepResult(source, 0, Instant.now());

        source.add(99L); // mutate original

        assertThat(result.getNewlyIgnitedCells()).doesNotContain(99L);
    }
}