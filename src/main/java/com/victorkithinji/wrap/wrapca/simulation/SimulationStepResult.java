package com.victorkithinji.wrap.wrapca.simulation;

import lombok.Value;

import java.time.Instant;
import java.util.Set;

/**
 * Immutable record of the outcome of one CA generation step.
 *
 * Produced by CaSpreadEngine after each generation and collected into a
 * List<SimulationStepResult> for later assembly into API response DTOs.
 *
 * Fields:
 *   newlyIgnitedCells  – encoded indices (row * cols + col) of cells that
 *                        transitioned UNBURNED → BURNING this generation.
 *   generation         – zero-based generation counter.
 *   timestamp          – wall-clock instant at which this step completed;
 *                        used for time-stamped perimeter snapshots in Phase 2.
 */
@Value
public class SimulationStepResult {

    Set<Long> newlyIgnitedCells;
    int       generation;
    Instant   timestamp;
}