package com.victorkithinji.wrap.wrapca.output;

import com.victorkithinji.wrap.wrapca.config.SimulationConfig;
import com.victorkithinji.wrap.wrapca.grid.CaGrid;
import com.victorkithinji.wrap.wrapca.grid.CellStateEnum;
import com.victorkithinji.wrap.wrapca.grid.VegetationTypeEnum;
import com.victorkithinji.wrap.wrapca.simulation.SimulationStepResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Derives {@link RunAnalytics} from completed simulation outputs.
 *
 * <p>Stateless. Both methods are pure functions of their inputs — no side effects,
 * no shared mutable state. Safe to call from multiple threads simultaneously.
 *
 * <p>Neither method throws. If inputs are inconsistent (e.g. array/grid size mismatch),
 * a warning is logged and an all-null {@link RunAnalytics} is returned so the caller's
 * response assembly is never blocked by analytics failure.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RunAnalyticsService {

	private static final int TOP_SEEDS_LIMIT = 5;

	private final SimulationConfig simulationConfig;

	/**
	 * Produces a Phase 1 analytics summary from Monte Carlo output arrays.
	 *
	 * <p>All Phase 2 fields on the returned object are null.
	 *
	 * @param damagePotentialValues Flat row-major array, length {@code rows × cols}.
	 * @param ignitionProbValues    Flat row-major array, length {@code rows × cols}.
	 * @param grid                  Fully initialised grid matching the arrays.
	 * @param totalRuns             Number of Monte Carlo runs that produced the arrays.
	 * @return Phase 1 analytics. Never null itself, but all fields may be null on error.
	 */
	public RunAnalytics summarisePhaseOne(
		float[] damagePotentialValues,
		float[] ignitionProbValues,
		CaGrid grid,
		int totalRuns) {

		int expected = grid.rows * grid.cols;

		if (damagePotentialValues.length != expected) {
			log.warn("summarisePhaseOne: damagePotentialValues length {} != grid cells {}; "
				+ "returning null analytics", damagePotentialValues.length, expected);
			return nullAnalytics();
		}

		// --- Step 1: 75th-percentile threshold and high-risk cell count ---
		float p75 = percentile75(damagePotentialValues);
		int highRiskCount = 0;
		for (float v : damagePotentialValues) {
			if (v >= p75) highRiskCount++;
		}

		// --- Step 2: high-risk area ---
		double cellSizeM = simulationConfig.getCellSizeMetres();
		double cellAreaHa = (cellSizeM * cellSizeM) / 10_000.0;
		double highRiskAreaHa = highRiskCount * cellAreaHa;

		// --- Step 3: top-5 ignition seeds by damage potential (descending) ---
		List<Long> topSeeds = topSeeds(damagePotentialValues, grid.cols);

		// --- Step 4: dominant vegetation type among high-risk cells ---
		String dominantVeg = dominantVegetationType(damagePotentialValues, p75, grid);

		return new RunAnalytics(
			highRiskCount,
			highRiskAreaHa,
			topSeeds,
			dominantVeg,
			null,   // finalBurnedAreaHectares
			null,   // averageRosHectaresPerHour
			null,   // generationsRun
			null    // perimeterCellCountFinal
		);
	}

	/**
	 * Produces a Phase 2 analytics summary from the engine's step results and final grid.
	 *
	 * <p>All Phase 1 fields on the returned object are null.
	 * If {@code steps} is empty, all Phase 2 fields are null except {@code generationsRun = 0}.
	 *
	 * @param steps  Ordered step results from {@code CaSpreadEngine.run()}.
	 * @param grid   Grid in its final state after all generations.
	 * @param config Simulation configuration — provides cell size and time-step.
	 * @return Phase 2 analytics. Never null itself.
	 */
	public RunAnalytics summarisePhaseTwo(
		List<SimulationStepResult> steps,
		CaGrid grid,
		SimulationConfig config) {

		int generationsRun = steps.size();

		if (generationsRun == 0) {
			return new RunAnalytics(
				null, null, null, null,  // phase 1 fields
				null, null,              // finalBurnedAreaHa, averageRos
				0,                       // generationsRun
				null                     // perimeterCellCount
			);
		}

		double cellSizeM = config.getCellSizeMetres();
		double cellAreaHa = (cellSizeM * cellSizeM) / 10_000.0;

		// --- Step 1: count BURNED cells and compute area ---
		int burnedCells = 0;
		for (int r = 0; r < grid.rows; r++) {
			for (int c = 0; c < grid.cols; c++) {
				if (grid.states[r][c] == CellStateEnum.BURNED.ordinal()) {
					burnedCells++;
				}
			}
		}
		double finalBurnedAreaHa = burnedCells * cellAreaHa;

		// --- Step 2: average ROS (only meaningful with >= 2 generations) ---
		Double averageRos = null;
		if (generationsRun >= 2) {
			double simulatedHours = (generationsRun * (double) config.getTimeStepMinutes()) / 60.0;
			averageRos = (simulatedHours > 0) ? finalBurnedAreaHa / simulatedHours : null;
		}

		// --- Step 3: perimeter cell count ---
		int perimeterCount = countBoundaryCells(grid);

		return new RunAnalytics(
			null, null, null, null,  // phase 1 fields
			finalBurnedAreaHa,
			averageRos,
			generationsRun,
			perimeterCount
		);
	}

	// -------------------------------------------------------------------------
	// Private helpers
	// -------------------------------------------------------------------------

	/**
	 * Returns the 75th-percentile value of {@code values} using the nearest-rank method.
	 * Sorts a copy so the original array is not modified.
	 */
	private float percentile75(float[] values) {
		float[] sorted = Arrays.copyOf(values, values.length);
		Arrays.sort(sorted);
		// nearest-rank: index = ceil(p/100 * n) - 1, clamped to [0, n-1]
		int idx = (int) Math.ceil(0.75 * sorted.length) - 1;
		idx = Math.max(0, Math.min(idx, sorted.length - 1));
		return sorted[idx];
	}

	/**
	 * Returns up to {@value #TOP_SEEDS_LIMIT} encoded cell indices with the highest
	 * damage potential values, in descending order.
	 */
	private List<Long> topSeeds(float[] damagePotentialValues, int cols) {
		// Build index list sorted by value descending
		Integer[] indices = new Integer[damagePotentialValues.length];
		for (int i = 0; i < indices.length; i++) indices[i] = i;

		Arrays.sort(indices, (a, b) -> Float.compare(damagePotentialValues[b], damagePotentialValues[a]));

		List<Long> seeds = new ArrayList<>(TOP_SEEDS_LIMIT);
		for (int i = 0; i < Math.min(TOP_SEEDS_LIMIT, indices.length); i++) {
			int flatIdx = indices[i];
			int row = flatIdx / cols;
			int col = flatIdx % cols;
			seeds.add((long) row * cols + col);
		}
		return Collections.unmodifiableList(seeds);
	}

	/**
	 * Finds the most frequent {@link VegetationTypeEnum} among cells whose damage
	 * potential is at or above {@code p75}. Ties are broken by lower ordinal.
	 *
	 * @return {@code VegetationType.name()} of the dominant type, or {@code null}
	 * if there are no high-risk cells (edge case: all values below p75 due
	 * to floating-point edge, which cannot happen in practice with nearest-rank
	 * but is handled defensively).
	 */
	private String dominantVegetationType(float[] damagePotentialValues, float p75, CaGrid grid) {
		Map<VegetationTypeEnum, Integer> freq = new EnumMap<>(VegetationTypeEnum.class);

		for (int r = 0; r < grid.rows; r++) {
			for (int c = 0; c < grid.cols; c++) {
				int idx = r * grid.cols + c;
				if (damagePotentialValues[idx] >= p75) {
					VegetationTypeEnum veg = grid.environment[r][c].getVegetationType();
					freq.merge(veg, 1, Integer::sum);
				}
			}
		}

		if (freq.isEmpty()) {
			log.warn("dominantVegetationType: no high-risk cells found — returning null");
			return null;
		}

		// Max frequency; tie-break by lower ordinal (deterministic)
		return freq.entrySet().stream()
			.max(Comparator
				.comparingInt(Map.Entry<VegetationTypeEnum, Integer>::getValue)
				.thenComparing(e -> -e.getKey().ordinal()))  // lower ordinal wins on tie
			.map(e -> e.getKey().name())
			.orElse(null);
	}

	/**
	 * Counts cells that are BURNING or BURNED and have at least one non-fire Moore
	 * neighbour or sit on the grid boundary.
	 * Mirrors the boundary logic in {@link PerimeterPolygonExtractorService} but
	 * returns a count rather than GeoJSON — no dependency on that service.
	 */
	private int countBoundaryCells(CaGrid grid) {
		int count = 0;
		for (int r = 0; r < grid.rows; r++) {
			for (int c = 0; c < grid.cols; c++) {
				int state = grid.states[r][c];
				if (state != CellStateEnum.BURNING.ordinal() && state != CellStateEnum.BURNED.ordinal()) {
					continue;
				}
				if (hasNonFireNeighbour(grid, r, c)) {
					count++;
				}
			}
		}
		return count;
	}

	private boolean hasNonFireNeighbour(CaGrid grid, int row, int col) {
		for (int dr = -1; dr <= 1; dr++) {
			for (int dc = -1; dc <= 1; dc++) {
				if (dr == 0 && dc == 0) continue;
				int nr = row + dr;
				int nc = col + dc;
				if (!grid.inBounds(nr, nc)) return true;
				int ns = grid.states[nr][nc];
				if (ns == CellStateEnum.UNBURNED.ordinal() || ns == CellStateEnum.NON_COMBUSTIBLE.ordinal()) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Returns a {@link RunAnalytics} with every field null — used on error paths.
	 */
	private RunAnalytics nullAnalytics() {
		return new RunAnalytics(null, null, null, null, null, null, null, null);
	}
}