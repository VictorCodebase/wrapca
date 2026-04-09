package com.victorkithinji.wrap.wrapca.dto.response;

import lombok.Value;

import java.util.List;

/**
 * Phase 2 API response.
 * Contains ordered perimeter snapshots — one per simulation generation
 * that produced newly ignited cells.
 */
@Value
public class PhaseTwoResultResponse {
	String runId;
	List<PerimeterSnapshot> perimetersByTimestamp;
}