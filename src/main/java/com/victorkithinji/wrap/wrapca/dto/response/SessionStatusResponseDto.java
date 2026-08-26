package com.victorkithinji.wrap.wrapca.dto.response;

import com.victorkithinji.wrap.wrapca.dto.SimulationModeEnum;
import lombok.Value;

import java.util.List;

/**
 * Response for GET /api/session/status.
 * <p>
 * Carries the current session mode and enough grid metadata for the frontend
 * to size its canvas and map pixel-space perimeter coordinates to UTM extents.
 * Past run summaries are included so the frontend can populate a run history
 * panel without a separate API call on load.
 */
@Value
public class SessionStatusResponseDto {

	public SessionStatusResponseDto(SimulationModeEnum mode, int rows, int cols,
							double cellSizeMetres, double minX, double minY,
							double maxX, double maxY,
							List<RunSummaryResponseDto> pastRuns,
							byte[] fuelRiskValues) {
		this.mode = mode;
		this.rows = rows;
		this.cols = cols;
		this.cellSizeMetres = cellSizeMetres;
		this.minX = minX;
		this.minY = minY;
		this.maxX = maxX;
		this.maxY = maxY;
		this.pastRuns = pastRuns;
		this.fuelRiskValues = fuelRiskValues;
	}

	public SessionStatusResponseDto(SimulationModeEnum mode, int rows, int cols,
							double cellSizeMetres, double minX, double minY,
							double maxX, double maxY,
							List<RunSummaryResponseDto> pastRuns) {
		this(mode, rows, cols, cellSizeMetres, minX, minY, maxX, maxY, pastRuns, new byte[] {});
	}

	/**
	 * Current operating mode. Determines which run endpoint is valid.
	 */
	SimulationModeEnum mode;

	// --- Grid summary -------------------------------------------------------

	/**
	 * Number of rows in the initialised CA grid (north–south).
	 */
	int rows;

	/**
	 * Number of columns in the initialised CA grid (east–west).
	 */
	int cols;

	/**
	 * Cell side length in metres (from wrap.simulation.cell-size-metres).
	 */
	double cellSizeMetres;

	/**
	 * Grid bounding box — western edge in UTM 37S metres.
	 * Together with maxX, maxY, rows, cols, and cellSizeMetres, the frontend
	 * can convert any pixel-space [col, row] coordinate to a map position.
	 */
	double minX;

	/**
	 * Grid bounding box — southern edge in UTM 37S metres.
	 */
	double minY;

	/**
	 * Grid bounding box — eastern edge in UTM 37S metres.
	 */
	double maxX;

	/**
	 * Grid bounding box — northern edge in UTM 37S metres.
	 */
	double maxY;

	// --- Run history --------------------------------------------------------

	/**
	 * Lightweight summaries of completed runs, most-recent first.
	 * Populated from RunLogReaderService. Empty list when no runs exist.
	 */
	List<RunSummaryResponseDto> pastRuns;

	byte[] fuelRiskValues;
}