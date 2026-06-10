package com.victorkithinji.wrap.wrapca.dto.request;

import com.victorkithinji.wrap.wrapca.dto.SimulationModeEnum;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Request body for POST /api/session/mode.
 * Allows a forest officer to manually override the session mode
 * from the dashboard without waiting for a CV satellite overpass.
 */
@Data
public class ModeOverrideRequestDto {

	/**
	 * Target mode. Required — a missing or null value returns HTTP 400.
	 * Valid values: PRE_FIRE, ACTIVE_FIRE.
	 */
	@NotNull(message = "mode must be PRE_FIRE or ACTIVE_FIRE")
	private SimulationModeEnum mode;
}