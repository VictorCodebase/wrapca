package com.victorkithinji.wrap.wrapca.dto.request;

import lombok.Data;

/**
 * Request body for POST /api/simulation/phase-one/run.
 * <p>
 * No fields are required. An empty JSON body {} is valid and triggers a
 * standard Monte Carlo run using the wind data loaded at session startup.
 * <p>
 * The optional wind overrides allow scenario testing without re-ingesting
 * ERA5 data — useful when the CV module is in stub mode.
 */
@Data
public class PhaseOneRunRequestDto {

	/**
	 * Override wind speed in m/s for this run only.
	 * Null means use the wind field loaded by WindFieldLoaderService.
	 * Applied uniformly across the entire grid when set.
	 */
	Double windSpeedMsOverride;

	/**
	 * Override wind FROM-direction in degrees (meteorological convention,
	 * 0–360, clockwise from north) for this run only.
	 * Null means use the wind field loaded by WindFieldLoaderService.
	 * Applied uniformly across the entire grid when set.
	 */
	Double windDirectionDegOverride;
}
