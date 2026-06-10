package com.victorkithinji.wrap.wrapca.output;

import com.victorkithinji.wrap.wrapca.dto.response.PhaseOneResultResponseDto;
import com.victorkithinji.wrap.wrapca.dto.response.PhaseTwoResultResponseDto;
import com.victorkithinji.wrap.wrapca.dto.response.PerimeterSnapshotDto;
import com.victorkithinji.wrap.wrapca.grid.CaGrid;
import com.victorkithinji.wrap.wrapca.simulation.SimulationStepResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts simulation outputs into API response DTOs.
 * Does not perform any simulation logic — reads grid state and step history only.
 * Does not emit GeoTIFF bytes or raw grid arrays.
 *
 * <p>Callers are responsible for computing {@link RunAnalytics} via
 * {@link RunAnalyticsService} before calling these methods and passing the result in.
 * Analytics computation is intentionally kept out of this class to preserve the
 * single-responsibility of each service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimulationResultAssemblerService {

	private final PerimeterPolygonExtractorService perimeterExtractor;

	/**
	 * Assembles a Phase 1 result from Monte Carlo output arrays, the final grid,
	 * and a pre-computed analytics summary.
	 *
	 * @param runId                 Unique identifier for this run.
	 * @param grid                  The grid used for this run — read-only here.
	 * @param damagePotentialValues Normalised burn-frequency values, one per cell, row-major.
	 * @param ignitionProbValues    Normalised I(c) values, one per cell, row-major.
	 * @param analytics             Pre-computed Phase 1 analytics from {@link RunAnalyticsService}.
	 * @return Populated {@link PhaseOneResultResponseDto}.
	 */
	public PhaseOneResultResponseDto assemblePhaseOne(
		String runId,
		CaGrid grid,
		float[] damagePotentialValues,
		float[] ignitionProbValues,
		RunAnalytics analytics) {

		int cellCount = grid.rows * grid.cols;

		if (damagePotentialValues.length != cellCount) {
			log.warn("damagePotentialValues length {} does not match grid cell count {}",
				damagePotentialValues.length, cellCount);
		}
		if (ignitionProbValues.length != cellCount) {
			log.warn("ignitionProbValues length {} does not match grid cell count {}",
				ignitionProbValues.length, cellCount);
		}

		int[] vegetationTypeOrdinals = extractVegetationOrdinals(grid);

		return new PhaseOneResultResponseDto(
			runId,
			damagePotentialValues,
			ignitionProbValues,
			vegetationTypeOrdinals,
			grid.rows,
			grid.cols,
			analytics);
	}

	/**
	 * Assembles a Phase 2 result from the ordered list of step results and a
	 * pre-computed analytics summary.
	 *
	 * <p>Each step that produced newly ignited cells yields a perimeter snapshot.
	 * Steps with no new ignitions are silently skipped.
	 *
	 * @param runId     Unique identifier for this run.
	 * @param grid      The grid in its final state after all generations.
	 * @param steps     Ordered list of step results from {@code CaSpreadEngine.run()}.
	 * @param analytics Pre-computed Phase 2 analytics from {@link RunAnalyticsService}.
	 * @return Populated {@link PhaseTwoResultResponseDto}.
	 */
	public PhaseTwoResultResponseDto assemblePhaseTwo(
		String runId,
		CaGrid grid,
		List<SimulationStepResult> steps,
		RunAnalytics analytics) {

		List<PerimeterSnapshotDto> snapshots = new ArrayList<>();

		for (SimulationStepResult step : steps) {
			if (step.getNewlyIgnitedCells().isEmpty()) {
				continue;
			}
			String geoJson = perimeterExtractor.extract(grid, step.getTimestamp());
			snapshots.add(new PerimeterSnapshotDto(geoJson, step.getTimestamp().toString()));
		}

		log.debug("Phase 2 result assembled: runId={}, generations={}, snapshots={}",
			runId, steps.size(), snapshots.size());

		return new PhaseTwoResultResponseDto(runId, snapshots, analytics);
	}

	// --- private helpers ---

	private int[] extractVegetationOrdinals(CaGrid grid) {
		int[] ordinals = new int[grid.rows * grid.cols];
		int idx = 0;
		for (int r = 0; r < grid.rows; r++) {
			for (int c = 0; c < grid.cols; c++) {
				ordinals[idx++] = grid.environment[r][c].getVegetationType().ordinal();
			}
		}
		return ordinals;
	}
}