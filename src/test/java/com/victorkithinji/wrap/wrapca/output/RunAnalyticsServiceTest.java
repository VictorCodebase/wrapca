package com.victorkithinji.wrap.wrapca.output;

import com.victorkithinji.wrap.wrapca.config.SimulationConfig;
import com.victorkithinji.wrap.wrapca.grid.CaGrid;
import com.victorkithinji.wrap.wrapca.grid.CellStateEnum;
import com.victorkithinji.wrap.wrapca.grid.VegetationTypeEnum;
import com.victorkithinji.wrap.wrapca.simulation.SimulationStepResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RunAnalyticsService}.
 * <p>
 * {@link SimulationConfig} is mocked so tests control cell size and time-step
 * precisely without relying on application.properties.
 * <p>
 * Tests are organised into:
 * - Phase 1: highRiskCellCount, highRiskAreaHectares, topIgnitionSeeds,
 * dominantVegetationType, null Phase 2 fields, error paths
 * - Phase 2: finalBurnedAreaHectares, averageRosHectaresPerHour,
 * generationsRun, perimeterCellCountFinal, empty steps,
 * null Phase 1 fields
 */
@DisplayName("RunAnalyticsService")
@ExtendWith(MockitoExtension.class)
class RunAnalyticsServiceTest {

	@Mock
	private SimulationConfig simulationConfig;

	private RunAnalyticsService analyticsService;

	/**
	 * 100m cell: 100×100 = 10 000 m² = 1 ha. Makes area arithmetic trivial to verify.
	 */
	private static final double CELL_SIZE_100M = 100.0;

	/**
	 * 200m cell: 200×200 = 40 000 m² = 4 ha per cell.
	 */
	private static final double CELL_SIZE_200M = 200.0;

	private static final int TIME_STEP_MINUTES = 5;

	@BeforeEach
	void setUp() {
		analyticsService = new RunAnalyticsService(simulationConfig);
	}

	// =========================================================================
	// PHASE 1
	// =========================================================================

	@Nested
	@DisplayName("summarisePhaseOne")
	class SummarisePhaseOne {

		@BeforeEach
		void stubConfig() {
			// lenient: tests that hit the mismatch early-return path never call
			// getCellSizeMetres(), so the stub would be flagged as unused by
			// Mockito strict mode. lenient() is correct here — this is shared
			// @BeforeEach setup that not every test in the nested class exercises.
			lenient().when(simulationConfig.getCellSizeMetres()).thenReturn(CELL_SIZE_100M);
		}

		// --- highRiskCellCount ---

		@Test
		@DisplayName("highRiskCellCount: cells at or above p75 are counted")
		void highRiskCellCount_atOrAboveP75() {
			// 4 cells, values 0.1 0.2 0.3 0.4 → sorted: [0.1,0.2,0.3,0.4]
			// p75 nearest-rank: ceil(0.75*4)-1 = idx 2 → value 0.3
			// cells >= 0.3: indices 2 and 3 → count 2
			CaGrid grid = GridTestFactory.allUnburned(2, 2);
			float[] dp = {0.1f, 0.2f, 0.3f, 0.4f};
			float[] ip = new float[4];

			RunAnalytics result = analyticsService.summarisePhaseOne(dp, ip, grid, 100);

			assertThat(result.getHighRiskCellCount()).isEqualTo(2);
		}

		@Test
		@DisplayName("highRiskCellCount: all cells identical → all are high-risk")
		void highRiskCellCount_uniformValues_allHighRisk() {
			CaGrid grid = GridTestFactory.allUnburned(2, 2);
			float[] dp = {0.5f, 0.5f, 0.5f, 0.5f};
			float[] ip = new float[4];

			RunAnalytics result = analyticsService.summarisePhaseOne(dp, ip, grid, 100);

			assertThat(result.getHighRiskCellCount()).isEqualTo(4);
		}

		@Test
		@DisplayName("highRiskCellCount: all-zero values → all cells at p75 → all high-risk")
		void highRiskCellCount_allZero_allHighRisk() {
			CaGrid grid = GridTestFactory.allUnburned(1, 4);
			float[] dp = {0f, 0f, 0f, 0f};
			float[] ip = new float[4];

			RunAnalytics result = analyticsService.summarisePhaseOne(dp, ip, grid, 100);

			assertThat(result.getHighRiskCellCount()).isEqualTo(4);
		}

		@Test
		@DisplayName("highRiskCellCount: single cell → that cell is high-risk")
		void highRiskCellCount_singleCell() {
			CaGrid grid = GridTestFactory.allUnburned(1, 1);
			float[] dp = {0.7f};
			float[] ip = new float[1];

			RunAnalytics result = analyticsService.summarisePhaseOne(dp, ip, grid, 1);

			assertThat(result.getHighRiskCellCount()).isEqualTo(1);
		}

		// --- highRiskAreaHectares ---

		@Test
		@DisplayName("highRiskAreaHectares: 100m cells, 2 high-risk cells = 2 ha")
		void highRiskArea_100mCells_twoHighRisk() {
			// dp = [0.1, 0.2, 0.3, 0.4] → 2 high-risk cells × 1 ha = 2.0 ha
			CaGrid grid = GridTestFactory.allUnburned(2, 2);
			float[] dp = {0.1f, 0.2f, 0.3f, 0.4f};
			float[] ip = new float[4];

			RunAnalytics result = analyticsService.summarisePhaseOne(dp, ip, grid, 100);

			assertThat(result.getHighRiskAreaHectares()).isEqualTo(2.0);
		}

		@Test
		@DisplayName("highRiskAreaHectares: 200m cells, 1 high-risk cell = 4 ha")
		void highRiskArea_200mCells() {
			when(simulationConfig.getCellSizeMetres()).thenReturn(CELL_SIZE_200M);
			CaGrid grid = GridTestFactory.allUnburned(1, 1);
			float[] dp = {1.0f};
			float[] ip = new float[1];

			RunAnalytics result = analyticsService.summarisePhaseOne(dp, ip, grid, 1);

			assertThat(result.getHighRiskAreaHectares()).isEqualTo(4.0);
		}

		@Test
		@DisplayName("highRiskAreaHectares: zero high-risk cells = 0.0 ha")
		void highRiskArea_zero() {
			// Force a scenario where no cells exceed p75 isn't possible with nearest-rank,
			// but a 1-cell grid guarantees exactly 1 high-risk cell (1.0 ha).
			// Test that the formula is applied: 1 cell × 1 ha = 1.0
			CaGrid grid = GridTestFactory.allUnburned(1, 1);
			float[] dp = {0.5f};
			float[] ip = new float[1];

			RunAnalytics result = analyticsService.summarisePhaseOne(dp, ip, grid, 1);

			assertThat(result.getHighRiskAreaHectares()).isEqualTo(1.0);
		}

		// --- topIgnitionSeeds ---

		@Test
		@DisplayName("topIgnitionSeeds: returns at most 5 seeds")
		void topSeeds_atMostFive() {
			CaGrid grid = GridTestFactory.allUnburned(2, 5); // 10 cells
			float[] dp = {0.9f, 0.8f, 0.7f, 0.6f, 0.5f, 0.4f, 0.3f, 0.2f, 0.1f, 0.05f};
			float[] ip = new float[10];

			RunAnalytics result = analyticsService.summarisePhaseOne(dp, ip, grid, 100);

			assertThat(result.getTopIgnitionSeeds()).hasSize(5);
		}

		@Test
		@DisplayName("topIgnitionSeeds: fewer than 5 cells returns all of them")
		void topSeeds_fewerThanFive() {
			CaGrid grid = GridTestFactory.allUnburned(1, 3);
			float[] dp = {0.9f, 0.5f, 0.1f};
			float[] ip = new float[3];

			RunAnalytics result = analyticsService.summarisePhaseOne(dp, ip, grid, 10);

			assertThat(result.getTopIgnitionSeeds()).hasSize(3);
		}

		@Test
		@DisplayName("topIgnitionSeeds: seeds are in descending order of damage potential")
		void topSeeds_descendingOrder() {
			// 1x4 grid, values in ascending order — top seed should be index 3 (highest value)
			CaGrid grid = GridTestFactory.allUnburned(1, 4);
			float[] dp = {0.1f, 0.2f, 0.8f, 0.5f};
			float[] ip = new float[4];

			RunAnalytics result = analyticsService.summarisePhaseOne(dp, ip, grid, 10);

			List<Long> seeds = result.getTopIgnitionSeeds();
			// First seed should encode the cell with dp=0.8 (flat index 2, row=0 col=2 → 0*4+2=2)
			assertThat(seeds.get(0)).isEqualTo(2L);
			// Second seed: dp=0.5 at index 3 → 3L
			assertThat(seeds.get(1)).isEqualTo(3L);
		}

		@Test
		@DisplayName("topIgnitionSeeds: encoded as row*cols+col")
		void topSeeds_encoding() {
			// 3x3 grid; highest value at (row=2, col=1) → encoded = 2*3+1 = 7
			CaGrid grid = GridTestFactory.allUnburned(3, 3);
			float[] dp = new float[9];
			dp[7] = 1.0f; // row=2, col=1
			float[] ip = new float[9];

			RunAnalytics result = analyticsService.summarisePhaseOne(dp, ip, grid, 10);

			assertThat(result.getTopIgnitionSeeds().get(0)).isEqualTo(7L);
		}

		@Test
		@DisplayName("topIgnitionSeeds: single cell grid returns one seed")
		void topSeeds_singleCell() {
			CaGrid grid = GridTestFactory.allUnburned(1, 1);
			float[] dp = {0.99f};
			float[] ip = new float[1];

			RunAnalytics result = analyticsService.summarisePhaseOne(dp, ip, grid, 1);

			assertThat(result.getTopIgnitionSeeds()).containsExactly(0L);
		}

		// --- dominantVegetationType ---

		@Test
		@DisplayName("dominantVegetationType: most frequent veg type among high-risk cells")
		void dominantVeg_mostFrequent() {
			// 2x2 grid; dp = [0.1, 0.2, 0.9, 0.8] → high-risk cells at indices 2 and 3
			// veg: (0,0)=SHRUBLAND, (0,1)=SHRUBLAND, (1,0)=GRASSLAND, (1,1)=GRASSLAND
			// high-risk indices 2 and 3 map to row=1 → both GRASSLAND
			CaGrid grid = GridTestFactory.withVegGrid(2, 2, (r, c) ->
				r == 0 ? VegetationTypeEnum.SHRUBLAND : VegetationTypeEnum.GRASSLAND);
			float[] dp = {0.1f, 0.2f, 0.9f, 0.8f};
			float[] ip = new float[4];

			RunAnalytics result = analyticsService.summarisePhaseOne(dp, ip, grid, 10);

			assertThat(result.getDominantVegetationType()).isEqualTo("GRASSLAND");
		}

		@Test
		@DisplayName("dominantVegetationType: tie broken by lower ordinal")
		void dominantVeg_tieBrokenByLowerOrdinal() {
			// 4 cells all high-risk (uniform value): 2 × GRASSLAND (ordinal 1) and 2 × SHRUBLAND (ordinal 2)
			// Tie: GRASSLAND wins (lower ordinal)
			CaGrid grid = GridTestFactory.withVegGrid(2, 2, (r, c) ->
				(r == 0) ? VegetationTypeEnum.GRASSLAND : VegetationTypeEnum.SHRUBLAND);
			float[] dp = {1.0f, 1.0f, 1.0f, 1.0f};
			float[] ip = new float[4];

			RunAnalytics result = analyticsService.summarisePhaseOne(dp, ip, grid, 10);

			assertThat(result.getDominantVegetationType()).isEqualTo("GRASSLAND");
		}

		@Test
		@DisplayName("dominantVegetationType: single cell returns that cell's type")
		void dominantVeg_singleCell() {
			CaGrid grid = GridTestFactory.uniformVeg(1, 1, VegetationTypeEnum.AFROMONTANE_FOREST);
			float[] dp = {0.5f};
			float[] ip = new float[1];

			RunAnalytics result = analyticsService.summarisePhaseOne(dp, ip, grid, 1);

			assertThat(result.getDominantVegetationType()).isEqualTo("AFROMONTANE_FOREST");
		}

		// --- Phase 2 fields are null ---

		@Test
		@DisplayName("all Phase 2 fields are null on a Phase 1 result")
		void phase2Fields_areNull() {
			CaGrid grid = GridTestFactory.allUnburned(2, 2);
			float[] dp = {0.1f, 0.2f, 0.3f, 0.4f};
			float[] ip = new float[4];

			RunAnalytics result = analyticsService.summarisePhaseOne(dp, ip, grid, 100);

			assertThat(result.getFinalBurnedAreaHectares()).isNull();
			assertThat(result.getAverageRosHectaresPerHour()).isNull();
			assertThat(result.getGenerationsRun()).isNull();
			assertThat(result.getPerimeterCellCountFinal()).isNull();
		}

		// --- error path: array/grid size mismatch ---

		@Test
		@DisplayName("array length mismatch returns all-null analytics without throwing")
		void mismatch_returnsNullAnalytics() {
			CaGrid grid = GridTestFactory.allUnburned(2, 2); // expects 4
			float[] dp = new float[3]; // wrong
			float[] ip = new float[4];

			RunAnalytics result = analyticsService.summarisePhaseOne(dp, ip, grid, 100);

			assertThat(result.getHighRiskCellCount()).isNull();
			assertThat(result.getHighRiskAreaHectares()).isNull();
			assertThat(result.getTopIgnitionSeeds()).isNull();
			assertThat(result.getDominantVegetationType()).isNull();
		}

		@Test
		@DisplayName("array length mismatch does not throw")
		void mismatch_doesNotThrow() {
			CaGrid grid = GridTestFactory.allUnburned(3, 3);
			float[] dp = new float[5]; // wrong — should be 9
			float[] ip = new float[9];

			assertThatCode(() -> analyticsService.summarisePhaseOne(dp, ip, grid, 100))
				.doesNotThrowAnyException();
		}
	}

	// =========================================================================
	// PHASE 2
	// =========================================================================

	@Nested
	@DisplayName("summarisePhaseTwo")
	class SummarisePhaseTwo {

		@BeforeEach
		void stubConfig() {
			// lenient: tests that check early-return paths (empty steps, null fields,
			// generationsRun only) never call getCellSizeMetres() or getTimeStepMinutes(),
			// so strict mode would flag them as unused. lenient() is the right choice
			// for shared @BeforeEach setup that not every test in the nested class uses.
			lenient().when(simulationConfig.getCellSizeMetres()).thenReturn(CELL_SIZE_100M);
			lenient().when(simulationConfig.getTimeStepMinutes()).thenReturn(TIME_STEP_MINUTES);
		}

		// --- generationsRun ---

		@Test
		@DisplayName("generationsRun equals steps.size()")
		void generationsRun_equalsStepCount() {
			CaGrid grid = GridTestFactory.allUnburned(3, 3);
			List<SimulationStepResult> steps = List.of(
				stubStep(), stubStep(), stubStep());

			RunAnalytics result = analyticsService.summarisePhaseTwo(steps, grid, simulationConfig);

			assertThat(result.getGenerationsRun()).isEqualTo(3);
		}

		@Test
		@DisplayName("generationsRun is 0 when steps list is empty")
		void generationsRun_emptySteps() {
			CaGrid grid = GridTestFactory.allUnburned(3, 3);

			RunAnalytics result = analyticsService.summarisePhaseTwo(List.of(), grid, simulationConfig);

			assertThat(result.getGenerationsRun()).isEqualTo(0);
		}

		// --- empty steps: all non-generationsRun fields are null ---

		@Test
		@DisplayName("empty steps returns null for all Phase 2 fields except generationsRun")
		void emptySteps_nullFieldsExceptGenerations() {
			CaGrid grid = GridTestFactory.allUnburned(3, 3);

			RunAnalytics result = analyticsService.summarisePhaseTwo(List.of(), grid, simulationConfig);

			assertThat(result.getFinalBurnedAreaHectares()).isNull();
			assertThat(result.getAverageRosHectaresPerHour()).isNull();
			assertThat(result.getPerimeterCellCountFinal()).isNull();
		}

		// --- finalBurnedAreaHectares ---

		@Test
		@DisplayName("finalBurnedAreaHectares: counts only BURNED cells, not BURNING")
		void burnedArea_countsOnlyBurnedNotBurning() {
			// 3 cells: BURNED, BURNING, UNBURNED — only the BURNED cell contributes
			// 100m cells: 1 BURNED cell = 1 ha
			CaGrid grid = GridTestFactory.build(1, 3,
				(r, c) -> switch (c) {
					case 0 -> CellStateEnum.BURNED;
					case 1 -> CellStateEnum.BURNING;
					default -> CellStateEnum.UNBURNED;
				},
				(r, c) -> GridTestFactory.DEFAULT_VEG);

			RunAnalytics result = analyticsService.summarisePhaseTwo(
				List.of(stubStep()), grid, simulationConfig);

			assertThat(result.getFinalBurnedAreaHectares()).isEqualTo(1.0);
		}

		@Test
		@DisplayName("finalBurnedAreaHectares: zero when no cells are BURNED")
		void burnedArea_zero_whenNoBurnedCells() {
			CaGrid grid = GridTestFactory.allUnburned(3, 3);

			RunAnalytics result = analyticsService.summarisePhaseTwo(
				List.of(stubStep()), grid, simulationConfig);

			assertThat(result.getFinalBurnedAreaHectares()).isEqualTo(0.0);
		}

		@Test
		@DisplayName("finalBurnedAreaHectares: all cells BURNED = rows*cols ha (at 100m)")
		void burnedArea_allBurned() {
			CaGrid grid = GridTestFactory.uniformState(3, 4, CellStateEnum.BURNED); // 12 cells = 12 ha
			RunAnalytics result = analyticsService.summarisePhaseTwo(
				List.of(stubStep()), grid, simulationConfig);

			assertThat(result.getFinalBurnedAreaHectares()).isEqualTo(12.0);
		}

		@Test
		@DisplayName("finalBurnedAreaHectares: scales with cell size — 200m cells are 4x larger")
		void burnedArea_scalesWithCellSize() {
			when(simulationConfig.getCellSizeMetres()).thenReturn(CELL_SIZE_200M);
			CaGrid grid = GridTestFactory.uniformState(1, 1, CellStateEnum.BURNED); // 1 cell = 4 ha

			RunAnalytics result = analyticsService.summarisePhaseTwo(
				List.of(stubStep()), grid, simulationConfig);

			assertThat(result.getFinalBurnedAreaHectares()).isEqualTo(4.0);
		}

		// --- averageRosHectaresPerHour ---

		@Test
		@DisplayName("averageRos: null when fewer than 2 generations ran")
		void averageRos_nullForSingleGeneration() {
			CaGrid grid = GridTestFactory.uniformState(2, 2, CellStateEnum.BURNED);

			RunAnalytics result = analyticsService.summarisePhaseTwo(
				List.of(stubStep()), grid, simulationConfig);

			assertThat(result.getAverageRosHectaresPerHour()).isNull();
		}

		@Test
		@DisplayName("averageRos: computed correctly for 2+ generations")
		void averageRos_computedForTwoGenerations() {
			// 12 burned cells × 1 ha = 12 ha
			// 2 generations × 5 min = 10 min = 10/60 hours
			// averageRos = 12 / (10/60) = 72 ha/hr
			CaGrid grid = GridTestFactory.uniformState(3, 4, CellStateEnum.BURNED);

			RunAnalytics result = analyticsService.summarisePhaseTwo(
				List.of(stubStep(), stubStep()), grid, simulationConfig);

			assertThat(result.getAverageRosHectaresPerHour())
				.isCloseTo(72.0, within(0.001));
		}

		@Test
		@DisplayName("averageRos: zero burned area gives 0.0 ha/hr (not NaN or error)")
		void averageRos_zeroBurnedArea() {
			CaGrid grid = GridTestFactory.allUnburned(3, 3); // no burned cells

			RunAnalytics result = analyticsService.summarisePhaseTwo(
				List.of(stubStep(), stubStep()), grid, simulationConfig);

			assertThat(result.getAverageRosHectaresPerHour()).isEqualTo(0.0);
		}

		// --- perimeterCellCountFinal ---

		@Test
		@DisplayName("perimeterCellCountFinal: interior all-BURNED cell is not a boundary cell")
		void perimeterCount_interiorCellExcluded() {
			// 3x3 all BURNED: only outer 8 cells are boundary; centre is not
			CaGrid grid = GridTestFactory.uniformState(3, 3, CellStateEnum.BURNED);

			RunAnalytics result = analyticsService.summarisePhaseTwo(
				List.of(stubStep()), grid, simulationConfig);

			assertThat(result.getPerimeterCellCountFinal()).isEqualTo(8);
		}

		@Test
		@DisplayName("perimeterCellCountFinal: 1x1 BURNED cell counts as boundary")
		void perimeterCount_singleBurnedCell() {
			CaGrid grid = GridTestFactory.singleCell(CellStateEnum.BURNED);

			RunAnalytics result = analyticsService.summarisePhaseTwo(
				List.of(stubStep()), grid, simulationConfig);

			assertThat(result.getPerimeterCellCountFinal()).isEqualTo(1);
		}

		@Test
		@DisplayName("perimeterCellCountFinal: zero when no fire cells exist")
		void perimeterCount_noFireCells() {
			CaGrid grid = GridTestFactory.allUnburned(4, 4);

			RunAnalytics result = analyticsService.summarisePhaseTwo(
				List.of(stubStep()), grid, simulationConfig);

			assertThat(result.getPerimeterCellCountFinal()).isEqualTo(0);
		}

		@Test
		@DisplayName("perimeterCellCountFinal: BURNING cells also count as boundary cells")
		void perimeterCount_burningCellsCount() {
			// Single BURNING cell surrounded by UNBURNED → boundary
			CaGrid grid = GridTestFactory.withStateAt(3, 3, 1, 1, CellStateEnum.BURNING);

			RunAnalytics result = analyticsService.summarisePhaseTwo(
				List.of(stubStep()), grid, simulationConfig);

			assertThat(result.getPerimeterCellCountFinal()).isEqualTo(1);
		}

		@Test
		@DisplayName("perimeterCellCountFinal: 5x5 all-BURNED outer ring = 16 cells")
		void perimeterCount_fiveByFiveAllBurned() {
			CaGrid grid = GridTestFactory.uniformState(5, 5, CellStateEnum.BURNED);

			RunAnalytics result = analyticsService.summarisePhaseTwo(
				List.of(stubStep()), grid, simulationConfig);

			// 5×5 - 3×3 interior = 25 - 9 = 16 boundary cells
			assertThat(result.getPerimeterCellCountFinal()).isEqualTo(16);
		}

		// --- Phase 1 fields are null ---

		@Test
		@DisplayName("all Phase 1 fields are null on a Phase 2 result")
		void phase1Fields_areNull() {
			CaGrid grid = GridTestFactory.allUnburned(2, 2);

			RunAnalytics result = analyticsService.summarisePhaseTwo(
				List.of(stubStep()), grid, simulationConfig);

			assertThat(result.getHighRiskCellCount()).isNull();
			assertThat(result.getHighRiskAreaHectares()).isNull();
			assertThat(result.getTopIgnitionSeeds()).isNull();
			assertThat(result.getDominantVegetationType()).isNull();
		}
	}

	// =========================================================================
	// Helpers
	// =========================================================================

	private SimulationStepResult stubStep() {
		SimulationStepResult step = mock(SimulationStepResult.class);
		lenient().when(step.getNewlyIgnitedCells()).thenReturn(Collections.emptySet());
		lenient().when(step.getTimestamp()).thenReturn(Instant.now());
		return step;
	}

	private static <T extends Comparable<T>> org.assertj.core.data.Offset<Double> within(double delta) {
		return org.assertj.core.data.Offset.offset(delta);
	}
}