package com.victorkithinji.wrap.wrapca.dto;

/**
 * Indicates whether the session is operating in pre-fire (Phase 1 Monte Carlo)
 * or active-fire (Phase 2 spread) mode.
 * <p>
 * Set by WrapSessionFacade at startup and on each CV poll refresh.
 * Embedded in SessionStatusResponseDto so the frontend knows which run
 * endpoint is currently valid.
 * <p>
 * Distinct from SimulationPhaseEnum (history package) which labels a
 * completed run record. This enum labels the live session state.
 */
public enum SimulationModeEnum {
	PRE_FIRE,
	ACTIVE_FIRE
}