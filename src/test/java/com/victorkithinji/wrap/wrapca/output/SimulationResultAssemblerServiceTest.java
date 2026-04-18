package com.victorkithinji.wrap.wrapca.output;

import com.victorkithinji.wrap.wrapca.dto.response.PhaseOneResultResponseDto;
import com.victorkithinji.wrap.wrapca.dto.response.PhaseTwoResultResponseDto;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SimulationResultAssemblerService}.
 * <p>
 * {@link PerimeterPolygonExtractorService} is mocked so these tests are isolated
 * to the assembler's own logic — specifically:
 * - DTO field population
 * - vegetationTypeOrdinals extraction
 * - phase two step filtering (only steps with ignitions produce snapshots)
 * - array length mismatch handling (warn but do not throw)
 * - empty step list handling
 * - runId propagation
 */
@DisplayName("SimulationResultAssemblerService")
@ExtendWith(MockitoExtension.class)
class SimulationResultAssemblerServiceTest {

	@Mock
	private PerimeterPolygonExtractorService perimeterExtractor;

	private SimulationResultAssemblerService assembler;

	private static final String RUN_ID = "run-001";
	private static final Instant T0 = Instant.parse("2025-06-15T08:00:00Z");
	private static final String STUB_GEOJSON = "{\"type\":\"FeatureCollection\",\"features\":[]}";

	@BeforeEach
	void setUp() {
		assembler = new SimulationResultAssemblerService(perimeterExtractor);
	}

	// -------------------------------------------------------------------------
	// Phase 1 — assemblePhaseOne
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("assemblePhaseOne")
	class AssemblePhaseOne {

		@Test
		@DisplayName("runId is propagated to the response")
		void runId_propagated() {
			CaGrid grid = GridTestFactory.allUnburned(2, 2);
			float[] dp = new float[4];
			float[] ip = new float[4];

			PhaseOneResultResponseDto response = assembler.assemblePhaseOne("my-run-xyz", grid, dp, ip);

			assertThat(response.getRunId()).isEqualTo("my-run-xyz");
		}

		@Test
		@DisplayName("rows and cols are taken from the grid")
		void rowsAndCols_fromGrid() {
			CaGrid grid = GridTestFactory.allUnburned(3, 7);
			float[] dp = new float[21];
			float[] ip = new float[21];

			PhaseOneResultResponseDto response = assembler.assemblePhaseOne(RUN_ID, grid, dp, ip);

			assertThat(response.getRows()).isEqualTo(3);
			assertThat(response.getCols()).isEqualTo(7);
		}

		@Test
		@DisplayName("damagePotentialValues array is the same reference passed in")
		void damagePotentialValues_sameReference() {
			CaGrid grid = GridTestFactory.allUnburned(2, 2);
			float[] dp = {0.1f, 0.2f, 0.3f, 0.4f};
			float[] ip = new float[4];

			PhaseOneResultResponseDto response = assembler.assemblePhaseOne(RUN_ID, grid, dp, ip);

			assertThat(response.getDamagePotentialValues()).isSameAs(dp);
		}

		@Test
		@DisplayName("ignitionProbabilityValues array is the same reference passed in")
		void ignitionProbabilityValues_sameReference() {
			CaGrid grid = GridTestFactory.allUnburned(2, 2);
			float[] dp = new float[4];
			float[] ip = {0.9f, 0.8f, 0.7f, 0.6f};

			PhaseOneResultResponseDto response = assembler.assemblePhaseOne(RUN_ID, grid, dp, ip);

			assertThat(response.getIgnitionProbabilityValues()).isSameAs(ip);
		}

		@Test
		@DisplayName("vegetationTypeOrdinals length equals rows * cols")
		void vegetationTypeOrdinals_correctLength() {
			CaGrid grid = GridTestFactory.allUnburned(4, 6);
			float[] dp = new float[24];
			float[] ip = new float[24];

			PhaseOneResultResponseDto response = assembler.assemblePhaseOne(RUN_ID, grid, dp, ip);

			assertThat(response.getVegetationTypeOrdinals()).hasSize(24);
		}

		@Test
		@DisplayName("vegetationTypeOrdinals are in row-major order")
		void vegetationTypeOrdinals_rowMajorOrder() {
			// 2x2 grid with distinct vegetation types per cell
			// (0,0)=AFROMONTANE_FOREST, (0,1)=GRASSLAND, (1,0)=SHRUBLAND, (1,1)=BARE_SOIL
			VegetationTypeEnum[][] vegLayout = {
				{VegetationTypeEnum.AFROMONTANE_FOREST, VegetationTypeEnum.GRASSLAND},
				{VegetationTypeEnum.SHRUBLAND, VegetationTypeEnum.BARE_SOIL}
			};
			CaGrid grid = GridTestFactory.withVegGrid(2, 2, (r, c) -> vegLayout[r][c]);
			float[] dp = new float[4];
			float[] ip = new float[4];

			PhaseOneResultResponseDto response = assembler.assemblePhaseOne(RUN_ID, grid, dp, ip);
			int[] ordinals = response.getVegetationTypeOrdinals();

			assertThat(ordinals[0]).isEqualTo(VegetationTypeEnum.AFROMONTANE_FOREST.ordinal());
			assertThat(ordinals[1]).isEqualTo(VegetationTypeEnum.GRASSLAND.ordinal());
			assertThat(ordinals[2]).isEqualTo(VegetationTypeEnum.SHRUBLAND.ordinal());
			assertThat(ordinals[3]).isEqualTo(VegetationTypeEnum.BARE_SOIL.ordinal());
		}

		@Test
		@DisplayName("vegetationTypeOrdinals all equal same ordinal when grid is uniform")
		void vegetationTypeOrdinals_uniformGrid() {
			CaGrid grid = GridTestFactory.uniformVeg(3, 3, VegetationTypeEnum.SHRUBLAND);
			float[] dp = new float[9];
			float[] ip = new float[9];

			PhaseOneResultResponseDto response = assembler.assemblePhaseOne(RUN_ID, grid, dp, ip);
			int[] ordinals = response.getVegetationTypeOrdinals();

			assertThat(ordinals).containsOnly(VegetationTypeEnum.SHRUBLAND.ordinal());
		}

		@Test
		@DisplayName("all VegetationTypeEnum values produce their correct ordinal")
		void allVegetationTypes_correctOrdinals() {
			VegetationTypeEnum[] types = VegetationTypeEnum.values();
			for (VegetationTypeEnum veg : types) {
				CaGrid grid = GridTestFactory.uniformVeg(1, 1, veg);
				float[] dp = new float[1];
				float[] ip = new float[1];

				PhaseOneResultResponseDto response = assembler.assemblePhaseOne(RUN_ID, grid, dp, ip);

				assertThat(response.getVegetationTypeOrdinals()[0])
					.as("ordinal for %s", veg)
					.isEqualTo(veg.ordinal());
			}
		}

		@Test
		@DisplayName("mismatched damagePotential array length does not throw")
		void mismatchedDamagePotentialLength_doesNotThrow() {
			CaGrid grid = GridTestFactory.allUnburned(2, 2); // expects 4 cells
			float[] dp = new float[3]; // wrong size
			float[] ip = new float[4];

			assertThatCode(() -> assembler.assemblePhaseOne(RUN_ID, grid, dp, ip))
				.doesNotThrowAnyException();
		}

		@Test
		@DisplayName("mismatched ignitionProb array length does not throw")
		void mismatchedIgnitionProbLength_doesNotThrow() {
			CaGrid grid = GridTestFactory.allUnburned(2, 2);
			float[] dp = new float[4];
			float[] ip = new float[10]; // wrong size

			assertThatCode(() -> assembler.assemblePhaseOne(RUN_ID, grid, dp, ip))
				.doesNotThrowAnyException();
		}

		@Test
		@DisplayName("1x1 grid produces single-element ordinals array")
		void oneByOneGrid_singleOrdinal() {
			CaGrid grid = GridTestFactory.uniformVeg(1, 1, VegetationTypeEnum.WATER);
			float[] dp = new float[1];
			float[] ip = new float[1];

			PhaseOneResultResponseDto response = assembler.assemblePhaseOne(RUN_ID, grid, dp, ip);

			assertThat(response.getVegetationTypeOrdinals()).hasSize(1);
			assertThat(response.getVegetationTypeOrdinals()[0]).isEqualTo(VegetationTypeEnum.WATER.ordinal());
		}
	}

	// -------------------------------------------------------------------------
	// Phase 2 — assemblePhaseTwo
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("assemblePhaseTwo")
	class AssemblePhaseTwo {

		@Test
		@DisplayName("runId is propagated to the response")
		void runId_propagated() {
			CaGrid grid = GridTestFactory.allUnburned(2, 2);
			PhaseTwoResultResponseDto response = assembler.assemblePhaseTwo("phase2-run", grid, List.of());
			assertThat(response.getRunId()).isEqualTo("phase2-run");
		}

		@Test
		@DisplayName("empty step list produces empty snapshots list")
		void emptyStepList_emptySnapshots() {
			CaGrid grid = GridTestFactory.allUnburned(3, 3);
			PhaseTwoResultResponseDto response = assembler.assemblePhaseTwo(RUN_ID, grid, List.of());
			assertThat(response.getPerimetersByTimestamp()).isEmpty();
			verifyNoInteractions(perimeterExtractor);
		}

		@Test
		@DisplayName("steps with no new ignitions produce no snapshots")
		void stepsWithNoIgnitions_noSnapshots() {
			CaGrid grid = GridTestFactory.allUnburned(3, 3);
			SimulationStepResult emptyStep = stepWithNoIgnitions(0, T0);

			PhaseTwoResultResponseDto response = assembler.assemblePhaseTwo(RUN_ID, grid, List.of(emptyStep));

			assertThat(response.getPerimetersByTimestamp()).isEmpty();
			verifyNoInteractions(perimeterExtractor);
		}

		@Test
		@DisplayName("step with new ignitions produces one snapshot")
		void stepWithIgnitions_producesOneSnapshot() {
			CaGrid grid = GridTestFactory.withStateAt(3, 3, 1, 1, CellStateEnum.BURNING);
			when(perimeterExtractor.extract(eq(grid), any())).thenReturn(STUB_GEOJSON);

			SimulationStepResult activeStep = stepWithIgnitions(0, T0, Set.of(4L));
			PhaseTwoResultResponseDto response = assembler.assemblePhaseTwo(RUN_ID, grid, List.of(activeStep));

			assertThat(response.getPerimetersByTimestamp()).hasSize(1);
		}

		@Test
		@DisplayName("snapshot contains GeoJSON from extractor")
		void snapshot_containsGeoJsonFromExtractor() {
			CaGrid grid = GridTestFactory.withStateAt(3, 3, 1, 1, CellStateEnum.BURNING);
			when(perimeterExtractor.extract(eq(grid), any())).thenReturn(STUB_GEOJSON);

			SimulationStepResult activeStep = stepWithIgnitions(0, T0, Set.of(4L));
			PhaseTwoResultResponseDto response = assembler.assemblePhaseTwo(RUN_ID, grid, List.of(activeStep));

			assertThat(response.getPerimetersByTimestamp().get(0).getPerimeterGeoJson())
				.isEqualTo(STUB_GEOJSON);
		}

		@Test
		@DisplayName("snapshot timestamp matches the step timestamp")
		void snapshot_timestampMatchesStep() {
			CaGrid grid = GridTestFactory.withStateAt(3, 3, 1, 1, CellStateEnum.BURNING);
			when(perimeterExtractor.extract(any(), any())).thenReturn(STUB_GEOJSON);

			SimulationStepResult activeStep = stepWithIgnitions(0, T0, Set.of(4L));
			PhaseTwoResultResponseDto response = assembler.assemblePhaseTwo(RUN_ID, grid, List.of(activeStep));

			assertThat(response.getPerimetersByTimestamp().get(0).getIsoTimestamp())
				.isEqualTo(T0.toString());
		}

		@Test
		@DisplayName("mixed steps: only active steps produce snapshots")
		void mixedSteps_onlyActiveProduceSnapshots() {
			CaGrid grid = GridTestFactory.withStateAt(5, 5, 2, 2, CellStateEnum.BURNING);
			when(perimeterExtractor.extract(any(), any())).thenReturn(STUB_GEOJSON);

			Instant t1 = T0;
			Instant t2 = T0.plusSeconds(300);
			Instant t3 = T0.plusSeconds(600);

			List<SimulationStepResult> steps = List.of(
				stepWithIgnitions(0, t1, Set.of(1L)),   // active
				stepWithNoIgnitions(1, t2),              // quiet
				stepWithIgnitions(2, t3, Set.of(3L))    // active
			);

			PhaseTwoResultResponseDto response = assembler.assemblePhaseTwo(RUN_ID, grid, steps);

			assertThat(response.getPerimetersByTimestamp()).hasSize(2);
			verify(perimeterExtractor, times(2)).extract(any(), any());
		}

		@Test
		@DisplayName("snapshot order matches step order")
		void snapshotOrder_matchesStepOrder() {
			CaGrid grid = GridTestFactory.withStateAt(5, 5, 2, 2, CellStateEnum.BURNING);

			Instant t1 = T0;
			Instant t2 = T0.plusSeconds(300);

			String geoJson1 = "{\"step\":1}";
			String geoJson2 = "{\"step\":2}";

			when(perimeterExtractor.extract(eq(grid), eq(t1))).thenReturn(geoJson1);
			when(perimeterExtractor.extract(eq(grid), eq(t2))).thenReturn(geoJson2);

			List<SimulationStepResult> steps = List.of(
				stepWithIgnitions(0, t1, Set.of(1L)),
				stepWithIgnitions(1, t2, Set.of(2L))
			);

			PhaseTwoResultResponseDto response = assembler.assemblePhaseTwo(RUN_ID, grid, steps);

			assertThat(response.getPerimetersByTimestamp().get(0).getPerimeterGeoJson()).isEqualTo(geoJson1);
			assertThat(response.getPerimetersByTimestamp().get(1).getPerimeterGeoJson()).isEqualTo(geoJson2);
		}

		@Test
		@DisplayName("extractor is called with the grid passed to assemblePhaseTwo")
		void extractor_calledWithCorrectGrid() {
			CaGrid grid = GridTestFactory.withStateAt(3, 3, 0, 0, CellStateEnum.BURNING);
			when(perimeterExtractor.extract(any(), any())).thenReturn(STUB_GEOJSON);

			SimulationStepResult step = stepWithIgnitions(0, T0, Set.of(0L));
			assembler.assemblePhaseTwo(RUN_ID, grid, List.of(step));

			verify(perimeterExtractor).extract(eq(grid), eq(T0));
		}

		@Test
		@DisplayName("all-quiet steps list produces empty snapshot list")
		void allQuietSteps_emptySnapshots() {
			CaGrid grid = GridTestFactory.allUnburned(3, 3);
			List<SimulationStepResult> steps = List.of(
				stepWithNoIgnitions(0, T0),
				stepWithNoIgnitions(1, T0.plusSeconds(300)),
				stepWithNoIgnitions(2, T0.plusSeconds(600))
			);

			PhaseTwoResultResponseDto response = assembler.assemblePhaseTwo(RUN_ID, grid, steps);

			assertThat(response.getPerimetersByTimestamp()).isEmpty();
			verifyNoInteractions(perimeterExtractor);
		}

		@Test
		@DisplayName("single all-active step list produces one snapshot")
		void singleActiveStep_oneSnapshot() {
			CaGrid grid = GridTestFactory.withStateAt(2, 2, 0, 0, CellStateEnum.BURNING);
			when(perimeterExtractor.extract(any(), any())).thenReturn(STUB_GEOJSON);

			PhaseTwoResultResponseDto response = assembler.assemblePhaseTwo(
				RUN_ID, grid, List.of(stepWithIgnitions(0, T0, Set.of(0L))));

			assertThat(response.getPerimetersByTimestamp()).hasSize(1);
		}
	}

	// -------------------------------------------------------------------------
	// Helpers — build SimulationStepResult stubs
	// -------------------------------------------------------------------------

	/**
	 * Creates a step result with no newly ignited cells.
	 * Only stubs getNewlyIgnitedCells() — the assembler short-circuits on empty
	 * sets and never reads generation or timestamp for quiet steps.
	 */
	private SimulationStepResult stepWithNoIgnitions(int generation, Instant timestamp) {
		SimulationStepResult step = mock(SimulationStepResult.class);
		when(step.getNewlyIgnitedCells()).thenReturn(Collections.emptySet());
		// generation and timestamp intentionally not stubbed — assembler never calls
		// them for steps that have no ignitions, and Mockito strict mode would flag
		// unused stubbings as an error.
		return step;
	}

	/**
	 * Creates a step result with the given set of newly ignited cell indices.
	 * Stubs getTimestamp() because the assembler reads it to populate the snapshot.
	 * Does not stub getGeneration() — the assembler does not call it.
	 */
	private SimulationStepResult stepWithIgnitions(int generation, Instant timestamp, Set<Long> cells) {
		SimulationStepResult step = mock(SimulationStepResult.class);
		when(step.getNewlyIgnitedCells()).thenReturn(Collections.unmodifiableSet(cells));
		when(step.getTimestamp()).thenReturn(timestamp);
		return step;
	}
}