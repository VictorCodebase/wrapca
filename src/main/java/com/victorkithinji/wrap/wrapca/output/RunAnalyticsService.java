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

/**
 * Derives {@link RunAnalytics} from completed simulation outputs.
 *
 * <p>Stateless. Both methods are pure functions of their inputs — no side effects,
 * no shared mutable state. Safe to call from multiple threads simultaneously.
 *
 * <p>Neither method throws. Inconsistent inputs (array/grid size mismatch) are
 * logged as warnings and return an all-null {@link RunAnalytics} so response
 * assembly is never blocked by analytics failure.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RunAnalyticsService {

	private static final int TOP_SEEDS_LIMIT = 5;

	private final SimulationConfig simulationConfig;

	// =========================================================================
	// Public API
	// =========================================================================

	/**
	 * Produces a Phase 1 analytics summary from Monte Carlo output arrays.
	 * All Phase 2 fields on the returned object are null.
	 *
	 * @param damagePotentialValues Flat row-major array, length rows x cols.
	 * @param ignitionProbValues    Flat row-major array, length rows x cols.
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

		double cellSizeM = simulationConfig.getCellSizeMetres();
		double cellAreaHa = (cellSizeM * cellSizeM) / 10_000.0;

		// 1. P75 threshold and high-risk cell count
		float p75 = percentile75(damagePotentialValues);
		int highRiskCount = 0;
		for (float v : damagePotentialValues) {
			if (v >= p75) highRiskCount++;
		}

		// 2. High-risk area total
		double highRiskAreaHa = highRiskCount * cellAreaHa;

		// 3. High-risk area broken down by vegetation type
		Map<String, Double> highRiskAreaByVeg =
			highRiskAreaByVegetationType(damagePotentialValues, p75, grid, cellAreaHa);

		// 4. Top-5 seeds with parallel scores
		int[] seedIndices = topSeedIndices(damagePotentialValues);
		List<Long> topSeeds = new ArrayList<>(seedIndices.length);
		List<Double> seedScores = new ArrayList<>(seedIndices.length);
		for (int flat : seedIndices) {
			int row = flat / grid.cols;
			int col = flat % grid.cols;
			topSeeds.add((long) row * grid.cols + col);
			seedScores.add((double) damagePotentialValues[flat]);
		}

		// 5. Dominant vegetation type
		String dominantVeg = dominantVegetationType(highRiskAreaByVeg);

		// 6. Simulated horizon
		double horizonHours = simulationConfig.getPhase1HorizonHours();

		return new RunAnalytics(
			highRiskCount,
			highRiskAreaHa,
			Collections.unmodifiableMap(highRiskAreaByVeg),
			Collections.unmodifiableList(topSeeds),
			Collections.unmodifiableList(seedScores),
			dominantVeg,
			horizonHours,
			// Phase 2 fields
			null, null, null, null, null, null, null, null, null
		);
	}

	/**
	 * Produces a Phase 2 analytics summary from step results and the final grid.
	 * All Phase 1 fields on the returned object are null.
	 * If steps is empty, all Phase 2 fields are null except generationsRun = 0.
	 *
	 * @param steps  Ordered step results from CaSpreadEngine.run().
	 * @param grid   Grid in its final state after all generations.
	 * @param config Simulation configuration — cell size and time-step.
	 * @return Phase 2 analytics. Never null itself.
	 */
	public RunAnalytics summarisePhaseTwo(
		List<SimulationStepResult> steps,
		CaGrid grid,
		SimulationConfig config) {

		int generationsRun = steps.size();

		if (generationsRun == 0) {
			return new RunAnalytics(
				null, null, null, null, null, null, null, // Phase 1 fields
				null, null, null, null, null, null, null, // Phase 2 fields (most)
				0d, 0                                         // generationsRun
			);
		}

		double cellSizeM = config.getCellSizeMetres();
		double cellAreaHa = (cellSizeM * cellSizeM) / 10_000.0;

		// 1. Burned area total + breakdown by vegetation type
		int burnedCells = 0;
		Map<VegetationTypeEnum, Integer> burnedByVeg = new EnumMap<>(VegetationTypeEnum.class);
		for (int r = 0; r < grid.rows; r++) {
			for (int c = 0; c < grid.cols; c++) {
				if (grid.states[r][c] == CellStateEnum.BURNED.ordinal()) {
					burnedCells++;
					VegetationTypeEnum veg = grid.environment[r][c].getVegetationType();
					burnedByVeg.merge(veg, 1, Integer::sum);
				}
			}
		}
		double finalBurnedAreaHa = burnedCells * cellAreaHa;

		Map<String, Double> burnedAreaByVeg = new LinkedHashMap<>();
		burnedByVeg.forEach((veg, count) ->
			burnedAreaByVeg.put(veg.name(), count * cellAreaHa));

		// 2. Peak ROS and step index — requires >= 2 generations to be meaningful
		Double peakRos = null;
		Integer stepAtPeak = null;
		if (generationsRun >= 2) {
			double timeStepHours = config.getTimeStepMinutes() / 60.0;
			double best = -1.0;
			for (SimulationStepResult step : steps) {
				double ros = step.getNewlyIgnitedCells().size() * cellAreaHa / timeStepHours;
				if (ros > best) {
					best = ros;
					stepAtPeak = step.getGeneration();
				}
			}
			peakRos = best;
		}

		// 3. Perimeter cell count and linear perimeter
		int perimeterCount = countBoundaryCells(grid);
		double perimeterMetres = perimeterCount * cellSizeM;

		// 4. Natural barrier cells adjacent to burned area
		int barrierCells = countNaturalBarrierCells(grid);

		// 5. Simulated duration
		double durationHours =
			(generationsRun * (double) config.getTimeStepMinutes()) / 60.0;

		return new RunAnalytics(
			null, null, null, null, null, null, null,       // Phase 1 fields
			finalBurnedAreaHa,
			Collections.unmodifiableMap(burnedAreaByVeg),
			peakRos,
			stepAtPeak,
			perimeterMetres,
			perimeterCount,
			barrierCells,
			durationHours,
			generationsRun
		);
	}

	// =========================================================================
	// Private helpers
	// =========================================================================

	/**
	 * Nearest-rank P75. Sorts a copy — does not modify the source array.
	 */
	private float percentile75(float[] values) {
		float[] sorted = Arrays.copyOf(values, values.length);
		Arrays.sort(sorted);
		int idx = (int) Math.ceil(0.75 * sorted.length) - 1;
		idx = Math.max(0, Math.min(idx, sorted.length - 1));
		return sorted[idx];
	}

	/**
	 * Returns the flat indices of the top-N cells by damage potential, descending.
	 * Uses a boxed Integer array so Arrays.sort can accept a comparator.
	 */
	private int[] topSeedIndices(float[] dp) {
		Integer[] indices = new Integer[dp.length];
		for (int i = 0; i < indices.length; i++) indices[i] = i;
		Arrays.sort(indices, (a, b) -> Float.compare(dp[b], dp[a]));
		int limit = Math.min(TOP_SEEDS_LIMIT, indices.length);
		int[] result = new int[limit];
		for (int i = 0; i < limit; i++) result[i] = indices[i];
		return result;
	}

	/**
	 * Builds a map of VegetationType name -> hectares among cells at or above p75.
	 */
	private Map<String, Double> highRiskAreaByVegetationType(
		float[] dp, float p75, CaGrid grid, double cellAreaHa) {

		Map<VegetationTypeEnum, Integer> freq = new EnumMap<>(VegetationTypeEnum.class);
		for (int r = 0; r < grid.rows; r++) {
			for (int c = 0; c < grid.cols; c++) {
				if (dp[r * grid.cols + c] >= p75) {
					freq.merge(grid.environment[r][c].getVegetationType(), 1, Integer::sum);
				}
			}
		}
		Map<String, Double> result = new LinkedHashMap<>();
		freq.forEach((veg, count) -> result.put(veg.name(), count * cellAreaHa));
		return result;
	}

	/**
	 * Returns the VegetationType name with the highest hectare value in the map.
	 * Tie-breaks by lower enum ordinal (deterministic).
	 */
	private String dominantVegetationType(Map<String, Double> areaByVeg) {
		if (areaByVeg.isEmpty()) {
			log.warn("dominantVegetationType: area map is empty — returning null");
			return null;
		}
		return areaByVeg.entrySet().stream()
			.max(Comparator
				.comparingDouble(Map.Entry<String, Double>::getValue)
				.thenComparing(e -> -VegetationTypeEnum.valueOf(e.getKey()).ordinal()))
			.map(Map.Entry::getKey)
			.orElse(null);
	}

	/**
	 * Counts fire cells that have at least one non-fire Moore neighbour or sit on the grid edge.
	 */
	private int countBoundaryCells(CaGrid grid) {
		int count = 0;
		for (int r = 0; r < grid.rows; r++) {
			for (int c = 0; c < grid.cols; c++) {
				int s = grid.states[r][c];
				if (s == CellStateEnum.BURNING.ordinal() || s == CellStateEnum.BURNED.ordinal()) {
					if (hasNonFireNeighbour(grid, r, c)) count++;
				}
			}
		}
		return count;
	}

	/**
	 * Counts NON_COMBUSTIBLE cells that are Moore-adjacent to at least one BURNED cell.
	 * These represent natural barriers the fire has reached — rivers, rock outcrops, built-up edges.
	 */
	private int countNaturalBarrierCells(CaGrid grid) {
		int count = 0;
		for (int r = 0; r < grid.rows; r++) {
			for (int c = 0; c < grid.cols; c++) {
				if (grid.states[r][c] != CellStateEnum.NON_COMBUSTIBLE.ordinal()) continue;
				outer:
				for (int dr = -1; dr <= 1; dr++) {
					for (int dc = -1; dc <= 1; dc++) {
						if (dr == 0 && dc == 0) continue;
						int nr = r + dr;
						int nc = c + dc;
						if (grid.inBounds(nr, nc)
							&& grid.states[nr][nc] == CellStateEnum.BURNED.ordinal()) {
							count++;
							break outer;
						}
					}
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
				if (ns == CellStateEnum.UNBURNED.ordinal()
					|| ns == CellStateEnum.NON_COMBUSTIBLE.ordinal()) return true;
			}
		}
		return false;
	}

	/**
	 * All-null instance returned on error paths.
	 */
	private RunAnalytics nullAnalytics() {
		return new RunAnalytics(
			null, null, null, null, null, null, null,
			null, null, null, null, null, null, null, null, null
		);
	}
}