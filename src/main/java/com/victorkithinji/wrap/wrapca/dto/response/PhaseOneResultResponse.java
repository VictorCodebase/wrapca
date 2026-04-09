package com.victorkithinji.wrap.wrapca.dto.response;

import lombok.Value;

/**
 * Phase 1 API response.
 * All three arrays are the same length: {@code rows * cols}, row-major.
 * {@code vegetationTypeOrdinals} values are {@code VegetationTypeEnum} ordinals (DEV-005).
 */
@Value
public class PhaseOneResultResponse {
	String runId;
	float[] damagePotentialValues;
	float[] ignitionProbabilityValues;
	int[] vegetationTypeOrdinals;
	int rows;
	int cols;
}