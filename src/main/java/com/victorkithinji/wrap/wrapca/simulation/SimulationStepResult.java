package com.victorkithinji.wrap.wrapca.simulation;

import lombok.Value;

import java.time.Instant;
import java.util.Collections;
import java.util.Set;

/**
 * Immutable snapshot of one CA generation step.
 * Collected into List<SimulationStepResult> by CaSpreadEngine and handed to output assembly.
 *
 * generation values are zero-based and sequential — list index == generation number.
 * newlyIgnitedCells is unmodifiable.
 */
@Value
public class SimulationStepResult {
    Set<Long> newlyIgnitedCells;
    int       generation;
    Instant   timestamp;

    public SimulationStepResult(Set<Long> newlyIgnitedCells, int generation, Instant timestamp) {
        this.newlyIgnitedCells = Collections.unmodifiableSet(newlyIgnitedCells);
        this.generation        = generation;
        this.timestamp         = timestamp;
    }
}