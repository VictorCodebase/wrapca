package com.victorkithinji.wrap.wrapca.output;

import lombok.Value;

import java.util.List;
import java.util.Map;

/**
 * Immutable analytics summary produced after a simulation run completes.
 *
 * <p>Phase 1 runs populate only Phase 1 fields; all Phase 2 fields are null.
 * Phase 2 runs populate only Phase 2 fields; all Phase 1 fields are null.
 * A null field means "not applicable to this run type" — never "data missing".
 *
 * <p>All numeric fields use boxed types so null is representable. Jackson
 * serialises null fields as JSON {@code null} — they are never suppressed.
 *
 * <p>Attached to both the HTTP response DTO and the persisted
 * {@code RunRecord} so analytics for past runs are available via
 * {@code GET /api/runs/{runId}} without re-running.
 */
@Value
public class RunAnalytics {

	// =========================================================================
	// Phase 1 fields  (null on Phase 2 runs)
	// =========================================================================

	/**
	 * Number of cells at or above the 75th percentile of damagePotentialValues.
	 */
	Integer highRiskCellCount;

	/**
	 * Total area covered by high-risk cells in hectares.
	 * highRiskCellCount x cellSizeMetres^2 / 10 000.
	 */
	Double highRiskAreaHectares;

	/**
	 * Hectares per vegetation type among high-risk cells.
	 * Keys are VegetationType.name() strings.
	 * Tells officers which fuel type is driving risk — forest vs grassland
	 * requires a different response.
	 * Example: {"GRASSLAND": 120.0, "AFROMONTANE_FOREST": 45.0}
	 */
	Map<String, Double> highRiskAreaByVegetationType;

	/**
	 * Up to 5 encoded cell indices (row * cols + col) with the highest
	 * burn-frequency values, descending. Most likely ignition origin points.
	 */
	List<Long> topIgnitionSeeds;

	/**
	 * Damage-potential score for each entry in topIgnitionSeeds, parallel by index.
	 * Range [0, 1]. Lets officers judge whether the top seed is a near-certainty
	 * (0.95) or only marginally elevated (0.51).
	 */
	List<Double> topIgnitionSeedScores;

	/**
	 * VegetationType name most represented among high-risk cells.
	 * Ties broken by lower ordinal.
	 */
	String dominantVegetationType;

	/**
	 * Simulated time horizon this Phase 1 risk map covers, in hours.
	 * Equals phase1HorizonHours from SimulationConfig.
	 */
	Double simulatedHorizonHours;

	// =========================================================================
	// Phase 2 fields  (null on Phase 1 runs)
	// =========================================================================

	/**
	 * Total area consumed (BURNED state only) at simulation end, in hectares.
	 */
	Double finalBurnedAreaHectares;

	/**
	 * Hectares per vegetation type among BURNED cells.
	 * Keys are VegetationType.name() strings.
	 * Example: {"GRASSLAND": 200.0, "AFROMONTANE_FOREST": 30.0}
	 */
	Map<String, Double> burnedAreaByVegetationType;

	/**
	 * Highest single-generation spread rate across all steps, in ha/hr.
	 * newlyIgnitedCells.size() x cellAreaHa / (timeStepMinutes / 60.0), maximised.
	 * This is the number that drives evacuation decisions — a peak of 120 ha/hr
	 * demands a different response than a mean of 30 ha/hr.
	 * Null when fewer than 2 generations ran.
	 */
	Double peakRosHectaresPerHour;

	/**
	 * Zero-based generation index at which peakRosHectaresPerHour occurred.
	 * Tells officers whether dangerous spread was early (wind-driven ignition)
	 * or mid-run (fire reaching dry fuel after escaping the forest edge).
	 * Null when fewer than 2 generations ran.
	 */
	Integer stepAtPeakRos;

	/**
	 * Approximate linear perimeter of the fire at simulation end, in metres.
	 * perimeterCellCountFinal x cellSizeMetres.
	 * Directly usable for resource deployment planning.
	 */
	Double perimeterLengthMetres;

	/**
	 * Count of boundary cells (BURNING or BURNED with at least one non-fire
	 * or out-of-bounds Moore neighbour) at simulation end.
	 */
	Integer perimeterCellCountFinal;

	/**
	 * Count of NON_COMBUSTIBLE cells Moore-adjacent to at least one BURNED cell.
	 * Approximates how much perimeter is bounded by natural barriers
	 * (water bodies, rocky outcrops from ESA classification).
	 * Helps officers judge whether the fire is naturally contained or open.
	 */
	Integer naturalBarrierCellsEncountered;

	/**
	 * Total simulated duration this Phase 2 run covers, in hours.
	 * generationsRun x timeStepMinutes / 60.0.
	 * Makes stored run records self-explaining without knowing the time-step config.
	 */
	Double simulatedDurationHours;

	/**
	 * Total number of CA generations the engine executed.
	 */
	Integer generationsRun;
}