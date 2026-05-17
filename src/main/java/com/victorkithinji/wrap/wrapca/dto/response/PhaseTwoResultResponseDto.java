package com.victorkithinji.wrap.wrapca.dto.response;

import com.victorkithinji.wrap.wrapca.output.RunAnalytics;
import lombok.Value;

import java.util.List;

/**
 * Response for POST /api/simulation/phase-two/run.
 * <p>
 * Contains one perimeter snapshot per generation step in which new cells
 * ignited. Steps with no new ignitions are omitted — the list may therefore
 * be shorter than the total generation count.
 */
@Value
public class PhaseTwoResultResponseDto {

	/**
	 * UUID assigned at run start.
	 */
	String runId;

	/**
	 * Ordered perimeter snapshots, one per generation with at least one
	 * newly ignited cell. Ordered by generation (ascending).
	 * Empty list if fire died out before any cell ignited (degenerate case).
	 */
	List<PerimeterSnapshotDto> perimetersByTimestamp;

	RunAnalytics analytics;
}