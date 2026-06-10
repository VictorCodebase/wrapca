package com.victorkithinji.wrap.wrapca.dto.response;

import lombok.Value;

/**
 * One perimeter snapshot from a Phase 2 simulation step.
 * Contained in PhaseTwoResultResponseDto.perimetersByTimestamp.
 * <p>
 * Only steps where new cells ignited produce a snapshot —
 * quiet steps (no new ignitions) are omitted.
 */
@Value
public class PerimeterSnapshotDto {

	/**
	 * GeoJSON FeatureCollection string from PerimeterPolygonExtractorService.
	 * Coordinates are pixel-space [col, row] integers.
	 * The frontend maps these to UTM using the grid bounds from SessionStatusResponseDto.
	 */
	String perimeterGeoJson;

	/**
	 * ISO-8601 wall-clock time at which this generation step completed.
	 */
	String isoTimestamp;
}