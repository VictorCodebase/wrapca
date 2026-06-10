package com.victorkithinji.wrap.wrapca.dto.response;

import lombok.Value;

/**
 * Static per-cell environmental data returned by GET /api/session/grid.
 * Built once at startup from CaGrid.environment and held until the next
 * session refresh. The frontend uses this to render the vegetation map
 * and topographic overlay before any simulation run.
 * <p>
 * All arrays are row-major: index = row * cols + col. Row 0 is northernmost.
 */
@Value
public class GridEnvironmentResponseDto {

	/**
	 * VegetationTypeEnum ordinals, one per cell.
	 */
	int[] vegetationTypeOrdinals;

	/**
	 * Elevation per cell in metres, sourced from CellEnvironment.elevationMetres.
	 */
	float[] elevationMetres;

	/**
	 * Slope magnitude per cell in degrees.
	 * CellEnvironment stores slope in radians — converted via Math.toDegrees()
	 * at assembly time in WrapSessionFacade.getGridEnvironment().
	 */
	float[] slopeDegrees;

	int rows;
	int cols;
	double cellSizeMetres;

	/**
	 * Grid west edge, UTM 37S metres.
	 */
	double minX;
	/**
	 * Grid south edge, UTM 37S metres.
	 */
	double minY;
	/**
	 * Grid east edge, UTM 37S metres.
	 */
	double maxX;
	/**
	 * Grid north edge, UTM 37S metres.
	 */
	double maxY;
}