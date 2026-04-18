package com.victorkithinji.wrap.wrapca.dto.request;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Request body for POST /api/simulation/phase-two/correct.
 * <p>
 * Carries a manual CV observation payload when the correction is triggered
 * by the frontend rather than by the scheduled CV poll in WrapSessionFacade.
 * <p>
 * All collections may be null or empty — CvStateInjectorService handles
 * partial correction payloads without error.
 */
@Data
public class CvCorrectionRequestDto {

	/**
	 * GeoJSON polygon of the observed fire perimeter at the time of correction.
	 * Used for display and logging. Not used for grid mutation directly —
	 * the cell index lists below drive state changes.
	 * May be null if the caller does not have a perimeter polygon.
	 */
	String observedPerimeterGeoJson;

	/**
	 * String-encoded cell IDs of suppressed zones.
	 * Each entry is a decimal string representation of the encoded cell index
	 * (row * gridCols + col). The facade parses these to long before passing
	 * them to CvStateInjectorService.
	 * <p>
	 * Null or empty list is valid — means no suppression to apply.
	 */
	List<String> suppressedZoneCellIds;

	/**
	 * Fresh NDMI values for UNBURNED cells.
	 * Keys are decimal string representations of encoded cell indices.
	 * Values are raw NDMI fractions (already scaled — the caller is
	 * responsible for providing Rothermel-compatible moisture fractions,
	 * not raw Sentinel-2 NDMI).
	 * <p>
	 * Null or empty map is valid — means no moisture update to apply.
	 */
	Map<String, Float> updatedMoistureValues;
}