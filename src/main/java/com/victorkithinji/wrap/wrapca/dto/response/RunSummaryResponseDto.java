package com.victorkithinji.wrap.wrapca.dto.response;

import com.victorkithinji.wrap.wrapca.dto.SimulationModeEnum;
import lombok.Value;

/**
 * Lightweight summary of one completed simulation run.
 * Assembled from RunRecord by WrapSessionFacade or RunHistoryController
 * for inclusion in SessionStatusResponseDto.pastRuns.
 * <p>
 * Deliberately thin — the full record (parameters map, result file path)
 * is available via GET /api/runs/{runId} and is not repeated here.
 */
@Value
public class RunSummaryResponseDto {

	/**
	 * UUID assigned at run start.
	 */
	String runId;

	/**
	 * PRE_FIRE or ACTIVE_FIRE.
	 * Mapped from SimulationPhaseEnum on the RunRecord — both share the
	 * same constant names so a simple name-based mapping works without
	 * introducing a cross-package dependency on the history package here.
	 */
	SimulationModeEnum phase;

	/**
	 * ISO-8601 wall-clock run start time.
	 */
	String startedAt;

	/**
	 * ISO-8601 wall-clock run completion time.
	 */
	String completedAt;
}