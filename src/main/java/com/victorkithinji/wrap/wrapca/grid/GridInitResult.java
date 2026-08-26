package com.victorkithinji.wrap.wrapca.grid;

import lombok.Value;

/**
 * Return type of {@link GridInitialiserService#build}.
 *
 * <p>Bundles the initialised {@link CaGrid}, the road-proximity array for
 * Group 7, and the fuel risk codes for the session status API response.
 * All three are produced in the same initialisation pass and travel together.
 */
@Value
public class GridInitResult {

	public GridInitResult(CaGrid grid, float[][] roadProximityMetres, byte[][] fuelRiskCodes) {
		this.grid = grid;
		this.roadProximityMetres = roadProximityMetres;
		this.fuelRiskCodes = fuelRiskCodes;
	}

	public GridInitResult(CaGrid grid, float[][] roadProximityMetres) {
		this(grid, roadProximityMetres, createZeroFuelRiskCodes(grid.rows, grid.cols));
	}

	private static byte[][] createZeroFuelRiskCodes(int rows, int cols) {
		byte[][] codes = new byte[rows][cols];
		for (int r = 0; r < rows; r++) {
			java.util.Arrays.fill(codes[r], (byte) 0);
		}
		return codes;
	}

	/**
	 * The fully initialised CA grid. Cell states are either
	 * {@link CellStateEnum#UNBURNED} or {@link CellStateEnum#NON_COMBUSTIBLE}.
	 */
	CaGrid grid;

	/**
	 * Minimum distance in metres from each cell centre to the nearest point
	 * on any road segment, indexed {@code [row][col]}.
	 *
	 * <p>A value of {@link Float#MAX_VALUE} indicates no road segments were
	 * present in the loaded {@link com.victorkithinji.wrap.wrapca.ingestion.RoadLayer}
	 * (e.g. the file was missing). Group 7 must treat MAX_VALUE as "no road
	 * influence" and zero out the road term in I(c) accordingly.
	 */
	float[][] roadProximityMetres;

	/**
	 * CV-derived fuel risk code per cell, indexed {@code [row][col]}.
	 * Values: 1 (low risk), 2 (medium risk), 3 (high risk), 0 (NoData).
	 * Dimensions always match {@code grid.rows × grid.cols}.
	 *
	 * <p>This array is pass-through data for the session status API response
	 * ({@code SessionStatusResponse.fuelRiskValues}). It is never read by the
	 * simulation engine. Group 12 flattens it to a row-major {@code byte[]}
	 * when building the response.
	 *
	 * <p>If the fuel risk file was absent at startup, this array is zero-filled
	 * by the facade — callers must treat 0 as "no data", not as low risk.
	 */
	byte[][] fuelRiskCodes;
}