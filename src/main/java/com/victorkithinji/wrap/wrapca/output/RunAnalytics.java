package com.victorkithinji.wrap.wrapca.output;

import lombok.Value;

import java.util.List;

/**
 * Immutable analytics summary produced after a simulation run completes.
 *
 * <p>Phase 1 runs populate only the Phase 1 fields; all Phase 2 fields are null.
 * Phase 2 runs populate only the Phase 2 fields; all Phase 1 fields are null.
 * This makes the distinction explicit in serialised output — a null field means
 * "not applicable to this run type", not "data missing".
 *
 * <p>All numeric fields use boxed types so null is representable. Jackson will
 * serialise null fields as JSON {@code null} — they are never suppressed.
 *
 * <p>This object is attached to both the HTTP response DTO and the persisted
 * {@code RunRecord}, so forest officers can retrieve analytics for past runs
 * via {@code GET /api/runs/{runId}} without re-running the simulation.
 */
@Value
public class RunAnalytics {

	// --- Phase 1 fields (null on Phase 2 runs) ---

	/**
	 * Number of cells whose damage potential value is at or above the 75th percentile
	 * across the full grid. High-risk cells are the primary actionable output of Phase 1.
	 */
	Integer highRiskCellCount;

	/**
	 * Total area of high-risk cells in hectares.
	 * {@code highRiskCellCount × cellSizeMetres² / 10 000}.
	 */
	Double highRiskAreaHectares;

	/**
	 * Up to 5 encoded cell indices ({@code row * cols + col}) with the highest
	 * burn-frequency values across the Monte Carlo ensemble, in descending order.
	 * These are the most likely ignition origin points given the current fuel state.
	 */
	List<Long> topIgnitionSeeds;

	/**
	 * Name of the {@code VegetationType} most represented among high-risk cells.
	 * Ties broken by lower ordinal (stable, deterministic).
	 * Gives field officers immediate context about which fuel type is driving risk.
	 */
	String dominantVegetationType;

	// --- Phase 2 fields (null on Phase 1 runs) ---

	/**
	 * Total burned area in hectares at simulation end.
	 * Counts only cells in BURNED state (not BURNING).
	 * {@code burnedCellCount × cellSizeMetres² / 10 000}.
	 */
	Double finalBurnedAreaHectares;

	/**
	 * Mean rate of spread expressed as hectares per hour over the full simulation.
	 * {@code finalBurnedAreaHectares / (generationsRun × timeStepMinutes / 60.0)}.
	 * Null when fewer than 2 generations ran (single-step run produces no meaningful rate).
	 */
	Double averageRosHectaresPerHour;

	/**
	 * Total number of CA generations the engine executed in this Phase 2 run.
	 * Zero when the fire never spread from the seed.
	 */
	Integer generationsRun;

	/**
	 * Number of boundary cells (BURNING or BURNED with at least one non-fire or
	 * out-of-bounds Moore neighbour) at simulation end.
	 * Approximates perimeter length in cell units — multiply by {@code cellSizeMetres}
	 * for a rough linear perimeter estimate.
	 */
	Integer perimeterCellCountFinal;
}