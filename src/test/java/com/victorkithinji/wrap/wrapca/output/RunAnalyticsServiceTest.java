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
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RunAnalyticsService}.
 * <p>
 * SimulationConfig is mocked so tests control cell size and time-step precisely.
 * All @BeforeEach stubs use lenient() because not every test in the nested class
 * reaches the lines that read cellSizeMetres or timeStepMinutes.
 * <p>
 * Cell size 100m is chosen throughout Phase 1 tests because 100×100 = 10 000 m²
 * = exactly 1 ha, making area arithmetic trivially verifiable by inspection.
 */
@DisplayName("RunAnalyticsService")
@ExtendWith(MockitoExtension.class)
class RunAnalyticsServiceTest {

	@Mock
	private SimulationConfig simulationConfig;

	private RunAnalyticsService analyticsService;

	private static final double CELL_100M = 100.0;   // 1 ha per cell
	private static final double CELL_200M = 200.0;   // 4 ha per cell
	private static final int TIME_STEP = 5;       // minutes

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
			lenient().when(simulationConfig.getCellSizeMetres()).thenReturn(CELL_100M);
			lenient().when(simulationConfig.getPhase1HorizonHours()).thenReturn(24);
		}

		// --- highRiskCellCount ---

		@Test
		@DisplayName("cells at or above p75 are counted as high-risk")
		void highRiskCount_atOrAboveP75() {
			// sorted: [0.1, 0.2, 0.3, 0.4] → p75 idx = ceil(0.75*4)-1 = 2 → value 0.3
			// cells >= 0.3: indices 2 and 3 → count 2
			CaGrid grid = GridTestFactory.allUnburned(2, 2);
			float[] dp = {0.1f, 0.2f, 0.3f, 0.4f};

			RunAnalytics r = analyticsService.summarisePhaseOne(dp, new float[4], grid, 100);

			assertThat(r.getHighRiskCellCount()).isEqualTo(2);
		}

		@Test
		@DisplayName("uniform values: all cells qualify as high-risk")
		void highRiskCount_uniformValues_allHighRisk() {
			CaGrid grid = GridTestFactory.allUnburned(2, 2);
			float[] dp = {0.5f, 0.5f, 0.5f, 0.5f};

			RunAnalytics r = analyticsService.summarisePhaseOne(dp, new float[4], grid, 100);

			assertThat(r.getHighRiskCellCount()).isEqualTo(4);
		}

		@Test
		@DisplayName("single cell: that cell is always high-risk")
		void highRiskCount_singleCell() {
			CaGrid grid = GridTestFactory.allUnburned(1, 1);
			RunAnalytics r = analyticsService.summarisePhaseOne(
				new float[]{0.7f}, new float[1], grid, 1);
			assertThat(r.getHighRiskCellCount()).isEqualTo(1);
		}

		// --- highRiskAreaHectares ---

		@Test
		@DisplayName("100m cells, 2 high-risk cells = 2.0 ha")
		void highRiskArea_100mCells_twoHighRisk() {
			CaGrid grid = GridTestFactory.allUnburned(2, 2);
			float[] dp = {0.1f, 0.2f, 0.3f, 0.4f}; // 2 high-risk cells

			RunAnalytics r = analyticsService.summarisePhaseOne(dp, new float[4], grid, 100);

			assertThat(r.getHighRiskAreaHectares()).isEqualTo(2.0);
		}

		@Test
		@DisplayName("200m cells, 1 high-risk cell = 4.0 ha")
		void highRiskArea_200mCells() {
			lenient().when(simulationConfig.getCellSizeMetres()).thenReturn(CELL_200M);
			CaGrid grid = GridTestFactory.allUnburned(1, 1);

			RunAnalytics r = analyticsService.summarisePhaseOne(
				new float[]{1.0f}, new float[1], grid, 1);

			assertThat(r.getHighRiskAreaHectares()).isEqualTo(4.0);
		}

		// --- highRiskAreaByVegetationType ---

		@Test
		@DisplayName("area breakdown key set matches vegetation types present in high-risk cells")
		void highRiskAreaByVeg_keySet() {
			// dp = [0.1, 0.2, 0.9, 0.8] → high-risk = indices 2 and 3 (row 1)
			// row 1 veg: GRASSLAND
			CaGrid grid = GridTestFactory.withVegGrid(2, 2,
				(r, c) -> r == 0 ? VegetationTypeEnum.SHRUBLAND : VegetationTypeEnum.GRASSLAND);
			float[] dp = {0.1f, 0.2f, 0.9f, 0.8f};

			RunAnalytics r = analyticsService.summarisePhaseOne(dp, new float[4], grid, 10);

			Map<String, Double> byVeg = r.getHighRiskAreaByVegetationType();
			assertThat(byVeg).containsKey("GRASSLAND");
			assertThat(byVeg).doesNotContainKey("SHRUBLAND");
		}

		@Test
		@DisplayName("area breakdown values sum to highRiskAreaHectares")
		void highRiskAreaByVeg_sumMatchesTotal() {
			CaGrid grid = GridTestFactory.withVegGrid(2, 2,
				(r, c) -> r == 0 ? VegetationTypeEnum.SHRUBLAND : VegetationTypeEnum.GRASSLAND);
			float[] dp = {0.9f, 0.8f, 0.7f, 0.6f}; // all high-risk (uniform p75 = 0.6)

			RunAnalytics r = analyticsService.summarisePhaseOne(dp, new float[4], grid, 10);

			double sum = r.getHighRiskAreaByVegetationType().values()
				.stream().mapToDouble(Double::doubleValue).sum();
			assertThat(sum).isCloseTo(r.getHighRiskAreaHectares(), within(1e-6));
		}

		// --- topIgnitionSeeds and topIgnitionSeedScores ---

		@Test
		@DisplayName("topIgnitionSeeds: at most 5 returned")
		void topSeeds_atMostFive() {
			CaGrid grid = GridTestFactory.allUnburned(2, 5); // 10 cells
			float[] dp = {0.9f, 0.8f, 0.7f, 0.6f, 0.5f, 0.4f, 0.3f, 0.2f, 0.1f, 0.05f};

			RunAnalytics r = analyticsService.summarisePhaseOne(dp, new float[10], grid, 100);

			assertThat(r.getTopIgnitionSeeds()).hasSize(5);
		}

		@Test
		@DisplayName("topIgnitionSeeds and topIgnitionSeedScores are parallel")
		void topSeeds_parallelScores() {
			CaGrid grid = GridTestFactory.allUnburned(1, 4);
			float[] dp = {0.1f, 0.2f, 0.8f, 0.5f};

			RunAnalytics r = analyticsService.summarisePhaseOne(dp, new float[4], grid, 10);

			assertThat(r.getTopIgnitionSeeds()).hasSameSizeAs(r.getTopIgnitionSeedScores());
		}

		@Test
		@DisplayName("topIgnitionSeeds: first seed is the cell with the highest dp value")
		void topSeeds_firstIsHighest() {
			// dp=0.8 is at flat index 2 (row=0, col=2) in a 1×4 grid → encoded = 2
			CaGrid grid = GridTestFactory.allUnburned(1, 4);
			float[] dp = {0.1f, 0.2f, 0.8f, 0.5f};

			RunAnalytics r = analyticsService.summarisePhaseOne(dp, new float[4], grid, 10);

			assertThat(r.getTopIgnitionSeeds().get(0)).isEqualTo(2L);
		}

		@Test
		@DisplayName("topIgnitionSeedScores: first score matches the highest dp value")
		void topSeedScores_firstMatchesHighest() {
			CaGrid grid = GridTestFactory.allUnburned(1, 4);
			float[] dp = {0.1f, 0.2f, 0.8f, 0.5f};

			RunAnalytics r = analyticsService.summarisePhaseOne(dp, new float[4], grid, 10);

			assertThat(r.getTopIgnitionSeedScores().get(0)).isCloseTo(0.8, within(1e-5));
		}

		@Test
		@DisplayName("topIgnitionSeeds: encoded as row*cols+col")
		void topSeeds_encoding() {
			// 3x3 grid; highest dp at flat index 7 (row=2, col=1) → encoded = 2*3+1 = 7
			CaGrid grid = GridTestFactory.allUnburned(3, 3);
			float[] dp = new float[9];
			dp[7] = 1.0f;

			RunAnalytics r = analyticsService.summarisePhaseOne(dp, new float[9], grid, 10);

			assertThat(r.getTopIgnitionSeeds().get(0)).isEqualTo(7L);
		}

		@Test
		@DisplayName("fewer than 5 cells: returns all of them")
		void topSeeds_fewerThanFive() {
			CaGrid grid = GridTestFactory.allUnburned(1, 3);
			RunAnalytics r = analyticsService.summarisePhaseOne(
				new float[]{0.9f, 0.5f, 0.1f}, new float[3], grid, 10);
			assertThat(r.getTopIgnitionSeeds()).hasSize(3);
		}

		// --- dominantVegetationType ---

		@Test
		@DisplayName("dominant type is the vegetation with the most high-risk area")
		void dominantVeg_mostArea() {
			// high-risk cells are row 1 (both GRASSLAND)
			CaGrid grid = GridTestFactory.withVegGrid(2, 2,
				(r, c) -> r == 0 ? VegetationTypeEnum.SHRUBLAND : VegetationTypeEnum.GRASSLAND);
			float[] dp = {0.1f, 0.2f, 0.9f, 0.8f};

			RunAnalytics r = analyticsService.summarisePhaseOne(dp, new float[4], grid, 10);

			assertThat(r.getDominantVegetationType()).isEqualTo("GRASSLAND");
		}

		@Test
		@DisplayName("tie in area: lower ordinal wins")
		void dominantVeg_tieBrokenByLowerOrdinal() {
			// all 4 cells high-risk: 2 × GRASSLAND (ordinal 1), 2 × SHRUBLAND (ordinal 2)
			CaGrid grid = GridTestFactory.withVegGrid(2, 2,
				(r, c) -> r == 0 ? VegetationTypeEnum.GRASSLAND : VegetationTypeEnum.SHRUBLAND);
			float[] dp = {1.0f, 1.0f, 1.0f, 1.0f};

			RunAnalytics r = analyticsService.summarisePhaseOne(dp, new float[4], grid, 10);

			assertThat(r.getDominantVegetationType()).isEqualTo("GRASSLAND");
		}

		// --- simulatedHorizonHours ---

		@Test
		@DisplayName("simulatedHorizonHours comes from SimulationConfig.getPhase1HorizonHours")
		void horizonHours_fromConfig() {
			lenient().when(simulationConfig.getPhase1HorizonHours()).thenReturn(48);
			CaGrid grid = GridTestFactory.allUnburned(1, 1);

			RunAnalytics r = analyticsService.summarisePhaseOne(
				new float[]{0.5f}, new float[1], grid, 1);

			assertThat(r.getSimulatedHorizonHours()).isEqualTo(48.0);
		}

		// --- Phase 2 fields are null ---

		@Test
		@DisplayName("all Phase 2 fields are null on a Phase 1 result")
		void phase2Fields_null() {
			CaGrid grid = GridTestFactory.allUnburned(2, 2);
			float[] dp = {0.1f, 0.2f, 0.3f, 0.4f};

			RunAnalytics r = analyticsService.summarisePhaseOne(dp, new float[4], grid, 100);

			assertThat(r.getFinalBurnedAreaHectares()).isNull();
			assertThat(r.getBurnedAreaByVegetationType()).isNull();
			assertThat(r.getPeakRosHectaresPerHour()).isNull();
			assertThat(r.getStepAtPeakRos()).isNull();
			assertThat(r.getPerimeterLengthMetres()).isNull();
			assertThat(r.getPerimeterCellCountFinal()).isNull();
			assertThat(r.getNaturalBarrierCellsEncountered()).isNull();
			assertThat(r.getSimulatedDurationHours()).isNull();
			assertThat(r.getGenerationsRun()).isNull();
		}

		// --- error path ---

		@Test
		@DisplayName("array/grid size mismatch returns all-null analytics without throwing")
		void mismatch_returnsNullAnalytics() {
			CaGrid grid = GridTestFactory.allUnburned(2, 2); // expects 4
			float[] dp = new float[3]; // wrong

			RunAnalytics r = analyticsService.summarisePhaseOne(dp, new float[4], grid, 100);

			assertThat(r.getHighRiskCellCount()).isNull();
			assertThat(r.getHighRiskAreaHectares()).isNull();
		}

		@Test
		@DisplayName("array/grid size mismatch does not throw")
		void mismatch_doesNotThrow() {
			CaGrid grid = GridTestFactory.allUnburned(3, 3);
			assertThatCode(() ->
				analyticsService.summarisePhaseOne(new float[5], new float[9], grid, 100))
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
			lenient().when(simulationConfig.getCellSizeMetres()).thenReturn(CELL_100M);
			lenient().when(simulationConfig.getTimeStepMinutes()).thenReturn(TIME_STEP);
		}

		// --- generationsRun ---

		@Test
		@DisplayName("generationsRun equals steps.size()")
		void generationsRun_equalsStepCount() {
			CaGrid grid = GridTestFactory.allUnburned(3, 3);
			RunAnalytics r = analyticsService.summarisePhaseTwo(
				List.of(stubStep(0), stubStep(1), stubStep(2)), grid, simulationConfig);
			assertThat(r.getGenerationsRun()).isEqualTo(3);
		}

		@Test
		@DisplayName("generationsRun is 0 for empty steps list")
		void generationsRun_emptySteps() {
			RunAnalytics r = analyticsService.summarisePhaseTwo(
				List.of(), GridTestFactory.allUnburned(3, 3), simulationConfig);
			assertThat(r.getGenerationsRun()).isEqualTo(0);
		}

		// --- empty steps: all other Phase 2 fields are null ---

		@Test
		@DisplayName("empty steps: all Phase 2 fields null except generationsRun")
		void emptySteps_otherFieldsNull() {
			RunAnalytics r = analyticsService.summarisePhaseTwo(
				List.of(), GridTestFactory.allUnburned(2, 2), simulationConfig);

			assertThat(r.getFinalBurnedAreaHectares()).isNull();
			assertThat(r.getBurnedAreaByVegetationType()).isNull();
			assertThat(r.getPeakRosHectaresPerHour()).isNull();
			assertThat(r.getStepAtPeakRos()).isNull();
			assertThat(r.getPerimeterLengthMetres()).isNull();
			assertThat(r.getPerimeterCellCountFinal()).isNull();
			assertThat(r.getNaturalBarrierCellsEncountered()).isNull();
			assertThat(r.getSimulatedDurationHours()).isNull();
		}

		// --- finalBurnedAreaHectares ---

		@Test
		@DisplayName("only BURNED cells count toward burned area — BURNING excluded")
		void burnedArea_burnedOnlyNotBurning() {
			// col0=BURNED(1 ha), col1=BURNING, col2=UNBURNED
			CaGrid grid = GridTestFactory.build(1, 3,
				(r, c) -> switch (c) {
					case 0 -> CellStateEnum.BURNED;
					case 1 -> CellStateEnum.BURNING;
					default -> CellStateEnum.UNBURNED;
				},
				(r, c) -> GridTestFactory.DEFAULT_VEG);

			RunAnalytics r = analyticsService.summarisePhaseTwo(
				List.of(stubStep(0)), grid, simulationConfig);

			assertThat(r.getFinalBurnedAreaHectares()).isEqualTo(1.0);
		}

		@Test
		@DisplayName("all-BURNED 3×4 grid = 12.0 ha at 100m cells")
		void burnedArea_allBurned() {
			CaGrid grid = GridTestFactory.uniformState(3, 4, CellStateEnum.BURNED);
			RunAnalytics r = analyticsService.summarisePhaseTwo(
				List.of(stubStep(0)), grid, simulationConfig);
			assertThat(r.getFinalBurnedAreaHectares()).isEqualTo(12.0);
		}

		@Test
		@DisplayName("200m cells: burned area scales correctly (4 ha per cell)")
		void burnedArea_200mCell() {
			lenient().when(simulationConfig.getCellSizeMetres()).thenReturn(CELL_200M);
			CaGrid grid = GridTestFactory.uniformState(1, 1, CellStateEnum.BURNED);
			RunAnalytics r = analyticsService.summarisePhaseTwo(
				List.of(stubStep(0)), grid, simulationConfig);
			assertThat(r.getFinalBurnedAreaHectares()).isEqualTo(4.0);
		}

		// --- burnedAreaByVegetationType ---

		@Test
		@DisplayName("burned area breakdown key set matches veg types of BURNED cells")
		void burnedByVeg_keySet() {
			// row 0 = BURNED GRASSLAND, row 1 = UNBURNED SHRUBLAND
			CaGrid grid = GridTestFactory.build(2, 2,
				(r, c) -> r == 0 ? CellStateEnum.BURNED : CellStateEnum.UNBURNED,
				(r, c) -> r == 0 ? VegetationTypeEnum.GRASSLAND : VegetationTypeEnum.SHRUBLAND);

			RunAnalytics r = analyticsService.summarisePhaseTwo(
				List.of(stubStep(0)), grid, simulationConfig);

			assertThat(r.getBurnedAreaByVegetationType()).containsKey("GRASSLAND");
			assertThat(r.getBurnedAreaByVegetationType()).doesNotContainKey("SHRUBLAND");
		}

		@Test
		@DisplayName("burned area breakdown values sum to finalBurnedAreaHectares")
		void burnedByVeg_sumMatchesTotal() {
			CaGrid grid = GridTestFactory.build(2, 2,
				(r, c) -> CellStateEnum.BURNED,
				(r, c) -> r == 0 ? VegetationTypeEnum.GRASSLAND : VegetationTypeEnum.SHRUBLAND);

			RunAnalytics r = analyticsService.summarisePhaseTwo(
				List.of(stubStep(0)), grid, simulationConfig);

			double sum = r.getBurnedAreaByVegetationType().values()
				.stream().mapToDouble(Double::doubleValue).sum();
			assertThat(sum).isCloseTo(r.getFinalBurnedAreaHectares(), within(1e-6));
		}

		// --- peakRosHectaresPerHour and stepAtPeakRos ---

		@Test
		@DisplayName("peakRos is null for single generation (< 2 required)")
		void peakRos_nullForOneGeneration() {
			CaGrid grid = GridTestFactory.allUnburned(3, 3);
			RunAnalytics r = analyticsService.summarisePhaseTwo(
				List.of(stubStep(0)), grid, simulationConfig);
			assertThat(r.getPeakRosHectaresPerHour()).isNull();
			assertThat(r.getStepAtPeakRos()).isNull();
		}

		@Test
		@DisplayName("peakRos: identifies the generation with the most newly ignited cells")
		void peakRos_identifiesPeakStep() {
			// step 0: 1 ignited, step 1: 5 ignited — peak is step 1
			CaGrid grid = GridTestFactory.allUnburned(5, 5);
			SimulationStepResult step0 = stubStepWithIgnitions(0, Set.of(1L));
			SimulationStepResult step1 = stubStepWithIgnitions(1, Set.of(2L, 3L, 4L, 5L, 6L));

			RunAnalytics r = analyticsService.summarisePhaseTwo(
				List.of(step0, step1), grid, simulationConfig);

			assertThat(r.getStepAtPeakRos()).isEqualTo(1);
		}

		@Test
		@DisplayName("peakRos value: 1 cell ignited per 5-min step at 100m = 12 ha/hr")
		void peakRos_value() {
			// 1 cell × 1 ha / (5 min / 60) = 1 / 0.08333 = 12.0 ha/hr
			CaGrid grid = GridTestFactory.allUnburned(3, 3);
			SimulationStepResult step0 = stubStepWithIgnitions(0, Set.of(1L));
			SimulationStepResult step1 = stubStepWithIgnitions(1, Set.of(2L));

			RunAnalytics r = analyticsService.summarisePhaseTwo(
				List.of(step0, step1), grid, simulationConfig);

			assertThat(r.getPeakRosHectaresPerHour()).isCloseTo(12.0, within(0.01));
		}

		// --- perimeterLengthMetres ---

		@Test
		@DisplayName("perimeterLengthMetres = perimeterCellCountFinal × cellSizeMetres")
		void perimeterLength_formula() {
			CaGrid grid = GridTestFactory.uniformState(3, 3, CellStateEnum.BURNED);
			RunAnalytics r = analyticsService.summarisePhaseTwo(
				List.of(stubStep(0)), grid, simulationConfig);

			int count = r.getPerimeterCellCountFinal();
			double expectedMetres = count * CELL_100M;
			assertThat(r.getPerimeterLengthMetres()).isEqualTo(expectedMetres);
		}

		@Test
		@DisplayName("3×3 all-BURNED: 8 boundary cells = 800m perimeter at 100m")
		void perimeterLength_threeByThree() {
			CaGrid grid = GridTestFactory.uniformState(3, 3, CellStateEnum.BURNED);
			RunAnalytics r = analyticsService.summarisePhaseTwo(
				List.of(stubStep(0)), grid, simulationConfig);

			assertThat(r.getPerimeterCellCountFinal()).isEqualTo(8);
			assertThat(r.getPerimeterLengthMetres()).isEqualTo(800.0);
		}

		@Test
		@DisplayName("1×1 BURNED: 1 boundary cell = 100m perimeter")
		void perimeterLength_singleCell() {
			CaGrid grid = GridTestFactory.singleCell(CellStateEnum.BURNED);
			RunAnalytics r = analyticsService.summarisePhaseTwo(
				List.of(stubStep(0)), grid, simulationConfig);

			assertThat(r.getPerimeterCellCountFinal()).isEqualTo(1);
			assertThat(r.getPerimeterLengthMetres()).isEqualTo(100.0);
		}

		// --- naturalBarrierCellsEncountered ---

		@Test
		@DisplayName("NON_COMBUSTIBLE cell adjacent to BURNED cell is a natural barrier")
		void naturalBarriers_counted() {
			// 1×2: col0=BURNED, col1=NON_COMBUSTIBLE
			CaGrid grid = GridTestFactory.build(1, 2,
				(r, c) -> c == 0 ? CellStateEnum.BURNED : CellStateEnum.NON_COMBUSTIBLE,
				(r, c) -> GridTestFactory.DEFAULT_VEG);

			RunAnalytics r = analyticsService.summarisePhaseTwo(
				List.of(stubStep(0)), grid, simulationConfig);

			assertThat(r.getNaturalBarrierCellsEncountered()).isEqualTo(1);
		}

		@Test
		@DisplayName("NON_COMBUSTIBLE cell not adjacent to any BURNED cell is not a barrier")
		void naturalBarriers_notAdjacentToFire_notCounted() {
			// 1×3: col0=UNBURNED, col1=UNBURNED, col2=NON_COMBUSTIBLE — no burned cells
			CaGrid grid = GridTestFactory.build(1, 3,
				(r, c) -> c == 2 ? CellStateEnum.NON_COMBUSTIBLE : CellStateEnum.UNBURNED,
				(r, c) -> GridTestFactory.DEFAULT_VEG);

			RunAnalytics r = analyticsService.summarisePhaseTwo(
				List.of(stubStep(0)), grid, simulationConfig);

			assertThat(r.getNaturalBarrierCellsEncountered()).isEqualTo(0);
		}

		@Test
		@DisplayName("no barriers when no NON_COMBUSTIBLE cells exist")
		void naturalBarriers_none() {
			CaGrid grid = GridTestFactory.uniformState(3, 3, CellStateEnum.BURNED);

			RunAnalytics r = analyticsService.summarisePhaseTwo(
				List.of(stubStep(0)), grid, simulationConfig);

			assertThat(r.getNaturalBarrierCellsEncountered()).isEqualTo(0);
		}

		// --- simulatedDurationHours ---

		@Test
		@DisplayName("simulatedDurationHours = generationsRun × timeStepMinutes / 60")
		void duration_formula() {
			// 6 generations × 5 min = 30 min = 0.5 hr
			CaGrid grid = GridTestFactory.allUnburned(2, 2);
			List<SimulationStepResult> steps = List.of(
				stubStep(0), stubStep(1), stubStep(2),
				stubStep(3), stubStep(4), stubStep(5));

			RunAnalytics r = analyticsService.summarisePhaseTwo(steps, grid, simulationConfig);

			assertThat(r.getSimulatedDurationHours()).isCloseTo(0.5, within(1e-6));
		}

		@Test
		@DisplayName("1 generation × 5 min = 0.0833... hours")
		void duration_oneGeneration() {
			CaGrid grid = GridTestFactory.allUnburned(2, 2);
			RunAnalytics r = analyticsService.summarisePhaseTwo(
				List.of(stubStep(0)), grid, simulationConfig);

			assertThat(r.getSimulatedDurationHours()).isCloseTo(5.0 / 60.0, within(1e-6));
		}

		// --- Phase 1 fields are null ---

		@Test
		@DisplayName("all Phase 1 fields are null on a Phase 2 result")
		void phase1Fields_null() {
			CaGrid grid = GridTestFactory.allUnburned(2, 2);
			RunAnalytics r = analyticsService.summarisePhaseTwo(
				List.of(stubStep(0)), grid, simulationConfig);

			assertThat(r.getHighRiskCellCount()).isNull();
			assertThat(r.getHighRiskAreaHectares()).isNull();
			assertThat(r.getHighRiskAreaByVegetationType()).isNull();
			assertThat(r.getTopIgnitionSeeds()).isNull();
			assertThat(r.getTopIgnitionSeedScores()).isNull();
			assertThat(r.getDominantVegetationType()).isNull();
			assertThat(r.getSimulatedHorizonHours()).isNull();
		}
	}

	// =========================================================================
	// Helpers
	// =========================================================================

	/**
	 * Stub step with no ignitions. Only stubs getGeneration() — the minimum required.
	 */
	private SimulationStepResult stubStep(int generation) {
		SimulationStepResult step = mock(SimulationStepResult.class);
		lenient().when(step.getGeneration()).thenReturn(generation);
		lenient().when(step.getNewlyIgnitedCells()).thenReturn(Collections.emptySet());
		return step;
	}

	/**
	 * Stub step with specific newly ignited cells.
	 */
	private SimulationStepResult stubStepWithIgnitions(int generation, Set<Long> cells) {
		SimulationStepResult step = mock(SimulationStepResult.class);
		when(step.getGeneration()).thenReturn(generation);
		when(step.getNewlyIgnitedCells()).thenReturn(Collections.unmodifiableSet(cells));
		return step;
	}

	private static org.assertj.core.data.Offset<Double> within(double delta) {
		return org.assertj.core.data.Offset.offset(delta);
	}
}