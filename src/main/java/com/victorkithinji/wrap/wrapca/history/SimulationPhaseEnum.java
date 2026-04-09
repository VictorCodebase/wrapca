package com.victorkithinji.wrap.wrapca.history;

/**
 * The two operational phases of a WRaP simulation run.
 * Used as a field on {@link RunRecord} and as part of the persisted filename.
 */
public enum SimulationPhaseEnum {

	/**
	 * Phase 1 — Monte Carlo ensemble producing ignition probability and damage potential maps.
	 */
	PRE_FIRE,

	/**
	 * Phase 2 — Rothermel-embedded active spread with CV correction at each overpass.
	 */
	ACTIVE_FIRE
}