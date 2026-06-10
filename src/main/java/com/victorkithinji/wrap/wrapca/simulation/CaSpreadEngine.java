package com.victorkithinji.wrap.wrapca.simulation;

import com.victorkithinji.wrap.wrapca.config.SimulationConfig;
import com.victorkithinji.wrap.wrapca.correction.SuppressedZoneRegistry;
import com.victorkithinji.wrap.wrapca.grid.CaGrid;
import com.victorkithinji.wrap.wrapca.grid.CellStateEnum;
import com.victorkithinji.wrap.wrapca.ingestion.WindField;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Core CA spread engine. Runs up to a fixed number of generation steps on a
 * mutable CaGrid, stopping early if the frontier empties.
 * <p>
 * Used by both Phase 1 (Monte Carlo, empty SuppressedZoneRegistry) and
 * Phase 2 (active spread, populated registry).
 * <p>
 * State transition order per generation:
 * 1. Snapshot the current frontier (synchronous CA semantics).
 * 2. For each frontier cell: check suppression, resolve Pe, roll rng.
 * 3. Advance all BURNING cells to BURNED.
 * 4. Apply newly ignited cells to BURNING.
 * 5. Update frontier tracker.
 * 6. Emit SimulationStepResult.
 * <p>
 * Thread safety: CaGrid is not thread-safe. The Monte Carlo runner must call
 * grid.deepCopy() before passing a copy to this method.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CaSpreadEngine {

	private final IgnitionProbabilityResolver probabilityResolver;
	private final SimulationConfig simulationConfig;

	// -------------------------------------------------------------------------
	// Public API
	// -------------------------------------------------------------------------

	/**
	 * Runs up to {@code generations} steps with a caller-supplied RNG.
	 * Mutates grid in place. Returns one SimulationStepResult per completed generation.
	 */
	public List<SimulationStepResult> run(CaGrid grid,
										  WindField windField,
										  SuppressedZoneRegistry suppressedZones,
										  int generations,
										  Random rng) {
		double timeStepMin = simulationConfig.getTimeStepMinutes();

		ActiveCellFrontierTracker frontier = new ActiveCellFrontierTracker();
		frontier.seedFromGrid(grid);

		List<SimulationStepResult> results = new ArrayList<>(generations);

		for (int gen = 0; gen < generations; gen++) {
			if (frontier.isEmpty()) {
				log.debug("Frontier empty at generation {}; stopping early.", gen);
				break;
			}

			// Step 1 — snapshot frontier (immutable view is sufficient; we iterate a copy)
			Set<Long> frontierSnapshot = new HashSet<>(frontier.getFrontier());

			// Step 2 — evaluate each frontier cell
			Set<Long> toIgnite = new HashSet<>();
			for (long cellIdx : frontierSnapshot) {
				if (suppressedZones.isActive(cellIdx)) continue;

				int r = grid.decodeRow(cellIdx);
				int c = grid.decodeCol(cellIdx);

				double pe = probabilityResolver.resolve(r, c, grid, windField, timeStepMin);
				log.debug("Cell {}, {} Pe: {}", r, c, pe);
				if (pe > 0.0 && rng.nextDouble() < pe) {
					toIgnite.add(cellIdx);
				}
			}

			// Step 3 — BURNING → BURNED
			Set<Long> newlyBurned = advanceBurningCells(grid);

			// Step 4 — UNBURNED → BURNING
			for (long cellIdx : toIgnite) {
				int r = grid.decodeRow(cellIdx);
				int c = grid.decodeCol(cellIdx);
				grid.setState(r, c, CellStateEnum.BURNING);
			}

			// Step 5 — update frontier
			frontier.onBurnOut(newlyBurned, grid);
			frontier.onIgnition(toIgnite, grid);

			// Step 6 — emit result
			results.add(new SimulationStepResult(toIgnite, gen, Instant.now()));

			log.trace("Gen {}: ignited={}, burned={}, frontier={}", gen,
				toIgnite.size(), newlyBurned.size(), frontier.getFrontier().size());
		}

		return results;
	}

	/**
	 * Convenience overload — creates a fresh Random internally.
	 */
	public List<SimulationStepResult> run(CaGrid grid,
										  WindField windField,
										  SuppressedZoneRegistry suppressedZones,
										  int generations) {
		return run(grid, windField, suppressedZones, generations, new Random());
	}

	// -------------------------------------------------------------------------
	// Internals
	// -------------------------------------------------------------------------

	/**
	 * Advances all currently BURNING cells to BURNED.
	 * Returns the set of encoded indices that transitioned.
	 */
	private Set<Long> advanceBurningCells(CaGrid grid) {
		Set<Long> newlyBurned = new HashSet<>();
		int rows = grid.rows;
		int cols = grid.cols;

		for (int r = 0; r < rows; r++) {
			for (int c = 0; c < cols; c++) {
				if (grid.getState(r, c) == CellStateEnum.BURNING) {
					grid.setState(r, c, CellStateEnum.BURNED);
					newlyBurned.add(grid.encodeIndex(r, c));
				}
			}
		}
		return newlyBurned;
	}
}