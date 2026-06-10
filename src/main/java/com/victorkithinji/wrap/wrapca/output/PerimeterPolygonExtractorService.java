package com.victorkithinji.wrap.wrapca.output;

import com.victorkithinji.wrap.wrapca.grid.CaGrid;
import com.victorkithinji.wrap.wrapca.grid.CellStateEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts the fire perimeter from a {@link CaGrid} as a GeoJSON polygon string.
 *
 * <p>Strategy: collect the four corner coordinates of every cell whose state is
 * BURNING or BURNED that has at least one UNBURNED or NON_COMBUSTIBLE neighbour
 * (i.e. sits on the fire boundary). The resulting coordinate set is returned as a
 * GeoJSON MultiPolygon of unit-cell bounding boxes.
 *
 * <p>This approach is deliberately simple — it produces a pixelated but correct
 * boundary that the frontend can render or simplify client-side. A full
 * marching-squares trace would reduce coordinate count but adds complexity with
 * no gain for the current use case.
 *
 * <p>Coordinates are in grid pixel space (column index, row index). The facade or
 * controller is responsible for projecting these to real-world coordinates if needed.
 * For the current Phase 2 API response the frontend receives these as pixel indices
 * and maps them to the known grid extent.
 *
 * <p>Returns an empty GeoJSON FeatureCollection when no fire cells exist.
 */
@Slf4j
@Service
public class PerimeterPolygonExtractorService {

	/**
	 * Extracts boundary cell boxes from the current grid state.
	 *
	 * @param grid      Grid in current simulation state.
	 * @param timestamp Observation time to embed in the GeoJSON properties.
	 * @return GeoJSON string — FeatureCollection with one Feature per boundary cell.
	 */
	public String extract(CaGrid grid, Instant timestamp) {
		List<int[]> boundaryCells = findBoundaryCells(grid);

		if (boundaryCells.isEmpty()) {
			log.debug("No boundary cells found at {}", timestamp);
			return emptyFeatureCollection(timestamp);
		}

		return buildFeatureCollection(boundaryCells, timestamp);
	}

	// --- private ---

	private List<int[]> findBoundaryCells(CaGrid grid) {
		List<int[]> cells = new ArrayList<>();
		for (int r = 0; r < grid.rows; r++) {
			for (int c = 0; c < grid.cols; c++) {
				CellStateEnum state = grid.getState(r, c);
				if (state == CellStateEnum.BURNING || state == CellStateEnum.BURNED) {
					if (hasNonFireNeighbour(grid, r, c)) {
						cells.add(new int[]{r, c});
					}
				}
			}
		}
		return cells;
	}

	private boolean hasNonFireNeighbour(CaGrid grid, int row, int col) {
		for (int dr = -1; dr <= 1; dr++) {
			for (int dc = -1; dc <= 1; dc++) {
				if (dr == 0 && dc == 0) continue;
				int nr = row + dr;
				int nc = col + dc;
				if (!grid.inBounds(nr, nc)) {
					// Grid edge counts as a boundary
					return true;
				}
				CellStateEnum ns = grid.getState(nr, nc);
				if (ns == CellStateEnum.UNBURNED || ns == CellStateEnum.NON_COMBUSTIBLE) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Builds a GeoJSON FeatureCollection. Each boundary cell becomes one Feature
	 * with a Polygon geometry representing its bounding box in [col, row] pixel space.
	 * The timestamp is embedded as a top-level property on the FeatureCollection.
	 */
	private String buildFeatureCollection(List<int[]> cells, Instant timestamp) {
		StringBuilder sb = new StringBuilder();
		sb.append("{\"type\":\"FeatureCollection\",\"timestamp\":\"")
			.append(timestamp)
			.append("\",\"features\":[");

		boolean first = true;
		for (int[] cell : cells) {
			if (!first) sb.append(",");
			first = false;
			int r = cell[0];
			int c = cell[1];
			// Bounding box corners in [col, row] order (GeoJSON x,y convention)
			sb.append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[")
				.append(c).append(",").append(r).append("],[")
				.append(c + 1).append(",").append(r).append("],[")
				.append(c + 1).append(",").append(r + 1).append("],[")
				.append(c).append(",").append(r + 1).append("],[")
				.append(c).append(",").append(r)
				.append("]]]},\"properties\":{}}");
		}

		sb.append("]}");
		return sb.toString();
	}

	private String emptyFeatureCollection(Instant timestamp) {
		return "{\"type\":\"FeatureCollection\",\"timestamp\":\"" + timestamp + "\",\"features\":[]}";
	}
}