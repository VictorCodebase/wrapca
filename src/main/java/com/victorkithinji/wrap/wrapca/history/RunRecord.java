package com.victorkithinji.wrap.wrapca.history;

import lombok.Value;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable record of a completed simulation run.
 * Serialised to JSON by {@link RunLogWriterService} and deserialised by {@link RunLogReaderService}.
 *
 * <p>All fields are set at construction — Lombok {@code @Value} generates the all-args
 * constructor, getters, equals, hashCode, and toString. Jackson deserialises via
 * {@code @JsonCreator} on the all-args constructor (enabled by the Lombok config).</p>
 */
@Value
public class RunRecord {

	/**
	 * Unique run identifier — UUID string, assigned by the facade at run start.
	 */
	String runId;

	/**
	 * Whether this was a PRE_FIRE (Phase 1) or ACTIVE_FIRE (Phase 2) run.
	 */
	SimulationPhaseEnum phase;

	/**
	 * Wall-clock time the run started.
	 */
	Instant startedAt;

	/**
	 * Wall-clock time the run completed.
	 */
	Instant completedAt;

	/**
	 * Key/value snapshot of the simulation parameters in effect for this run.
	 * Sourced from {@code SimulationConfig} fields at the time of the run.
	 * Values are primitives boxed as {@code Object} — safe for JSON round-trip.
	 */
	Map<String, Object> parameters;

	/**
	 * Relative path to the persisted result file for this run, if one was written.
	 * {@code null} when no result file was produced (e.g. the run completed but
	 * {@link RunLogWriterService} was called without a result path).
	 */
	String resultFilePath;
}