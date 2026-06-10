package com.victorkithinji.wrap.wrapca.dto.response;

import com.victorkithinji.wrap.wrapca.output.RunAnalytics;
import lombok.Value;

/**
 * Response for POST /api/simulation/phase-one/run.
 * <p>
 * Three parallel flat arrays, all row-major, all length rows × cols.
 * The frontend indexes them as: index = row * cols + col.
 * <p>
 * Field names match the stubs defined in output-package-contract exactly —
 * SimulationResultAssemblerService constructs this type directly.
 */
@Value
public class PhaseOneResultResponseDto {

	/**
	 * UUID assigned at run start.
	 */
	String runId;

	/**
	 * Normalised burn frequency per cell across N Monte Carlo runs.
	 * Values in [0.0, 1.0]. 0 = never burned, 1 = burned in every run.
	 * Length: rows × cols.
	 */
	float[] damagePotentialValues;

	/**
	 * Normalised ignition likelihood index I(c) per cell.
	 * Values in [0.0, 1.0].
	 * Length: rows × cols.
	 */
	float[] ignitionProbabilityValues;

	/**
	 * VegetationTypeEnum ordinal per cell, row-major.
	 * Provides land-cover context for the frontend heatmap overlay.
	 * Length: rows × cols. See DEV-005.
	 */
	int[] vegetationTypeOrdinals;

	/**
	 * Grid row count.
	 */
	int rows;

	/**
	 * Grid column count.
	 */
	int cols;

	RunAnalytics analytics;
}