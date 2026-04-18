package com.victorkithinji.wrap.wrapca.dto.request;

import lombok.Data;

/**
 * Request body for POST /api/simulation/phase-two/run.
 * <p>
 * Controls how Phase 2 is seeded and how long it runs.
 * All boolean fields default to false (standard CV-driven run).
 */
@Data
public class PhaseTwoRunRequestDto {

	/**
	 * When true, the CV perimeter check is bypassed and no CV corrections
	 * are applied during the run. The simulation uses only the initial
	 * grid state and the loaded wind field.
	 * Default: false.
	 */
	boolean cvDisabled;

	/**
	 * When true, the fire is seeded from manualIgnitionPolygonGeoJson
	 * rather than from the CV fire perimeter endpoint.
	 * Requires manualIgnitionPolygonGeoJson to be non-null.
	 * Default: false.
	 */
	boolean manualIgnition;

	/**
	 * GeoJSON polygon defining the manual ignition area.
	 * Required when manualIgnition is true; ignored otherwise.
	 * Coordinates must be in UTM 37S metres to match the grid CRS.
	 * Null is valid when manualIgnition is false.
	 */
	String manualIgnitionPolygonGeoJson;

	/**
	 * Number of simulated hours to run Phase 2.
	 * The engine converts this to generation count using
	 * (simulationHours * 60) / timeStepMinutes.
	 * Must be > 0.
	 */
	int simulationHours;
}