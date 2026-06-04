package com.victorkithinji.wrap.wrapca.facade;

import com.victorkithinji.wrap.wrapca.config.SimulationConfig;
import com.victorkithinji.wrap.wrapca.correction.CvStateInjectorService;
import com.victorkithinji.wrap.wrapca.correction.SuppressedZoneRegistry;
import com.victorkithinji.wrap.wrapca.cvintegration.CvApiClient;
import com.victorkithinji.wrap.wrapca.cvintegration.FirePerimeterData;
import com.victorkithinji.wrap.wrapca.dto.SimulationModeEnum;
import com.victorkithinji.wrap.wrapca.dto.request.CvCorrectionRequestDto;
import com.victorkithinji.wrap.wrapca.dto.request.PhaseOneRunRequestDto;
import com.victorkithinji.wrap.wrapca.dto.request.PhaseTwoRunRequestDto;
import com.victorkithinji.wrap.wrapca.dto.response.PhaseOneResultResponseDto;
import com.victorkithinji.wrap.wrapca.dto.response.PhaseTwoResultResponseDto;
import com.victorkithinji.wrap.wrapca.dto.response.SessionStatusResponseDto;
import com.victorkithinji.wrap.wrapca.grid.CaGrid;
import com.victorkithinji.wrap.wrapca.grid.CellEnvironment;
import com.victorkithinji.wrap.wrapca.grid.CellStateEnum;
import com.victorkithinji.wrap.wrapca.grid.GridInitResult;
import com.victorkithinji.wrap.wrapca.grid.GridInitialiserService;
import com.victorkithinji.wrap.wrapca.grid.VegetationTypeEnum;
import com.victorkithinji.wrap.wrapca.history.RunLogReaderService;
import com.victorkithinji.wrap.wrapca.history.RunLogWriterService;
import com.victorkithinji.wrap.wrapca.history.RunRecord;

import com.victorkithinji.wrap.wrapca.ingestion.*;
import com.victorkithinji.wrap.wrapca.montecarlo.*;
import com.victorkithinji.wrap.wrapca.output.RunAnalytics;
import com.victorkithinji.wrap.wrapca.output.RunAnalyticsService;
import com.victorkithinji.wrap.wrapca.output.SimulationResultAssemblerService;
import com.victorkithinji.wrap.wrapca.simulation.CaSpreadEngine;
import com.victorkithinji.wrap.wrapca.simulation.SimulationStepResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WrapSessionFacadeTest {

	// --- Mocks ---
	@Mock
	SimulationConfig simulationConfig;
	@Mock
	CvApiClient cvApiClient;
	@Mock
	GeoTiffBandReaderService geoTiffBandReaderService;
	@Mock
	OsmRoadLoaderService osmRoadLoaderService;
	@Mock
	RasterResamplerService rasterResamplerService;
	@Mock
	GridInitialiserService gridInitialiserService;
	@Mock
	WindFieldLoaderService windFieldLoaderService;
	@Mock
	FirePerimeterParserService firePerimeterParserService;
	@Mock
	IgnitionLikelihoodIndexBuilder icBuilder;
	@Mock
	IgnitionSeedSampler seedSampler;
	@Mock
	MonteCarloEnsembleRunner ensembleRunner;
	@Mock
	RiskMapAssembler riskMapAssembler;
	@Mock
	CaSpreadEngine caSpreadEngine;
	@Mock
	CvStateInjectorService cvStateInjectorService;
	@Mock
	SuppressedZoneRegistry suppressedZoneRegistry;
	@Mock
	SimulationResultAssemblerService resultAssemblerService;
	@Mock
	RunAnalyticsService analyticsService;
	@Mock
	RunLogWriterService runLogWriterService;
	@Mock
	RunLogReaderService runLogReaderService;

	private WrapSessionFacade facade;

	// Shared test fixtures
	private CaGrid grid;
	private WindField windField;
	private GridInitResult gridInitResult;
	private GridBands resampledBands;

	@BeforeEach
	void setUp() throws Exception {
		facade = new WrapSessionFacade(
			simulationConfig, cvApiClient,
			geoTiffBandReaderService, osmRoadLoaderService,
			rasterResamplerService, gridInitialiserService,
			windFieldLoaderService, firePerimeterParserService,
			icBuilder, seedSampler, ensembleRunner, riskMapAssembler,
			caSpreadEngine, cvStateInjectorService, suppressedZoneRegistry,
			resultAssemblerService, analyticsService,
			runLogWriterService, runLogReaderService);

		// Inject @Value field that Spring would normally inject
		ReflectionTestUtils.setField(facade, "esaPath", "./data/esa/esa_worldcover.tif");

		// Build a minimal 2×2 grid
		CellEnvironment env = new CellEnvironment(0.5f, 0.1f, 1500f, 0.1f, 1.0f,
			VegetationTypeEnum.GRASSLAND);
		CellEnvironment[][] envGrid = {{env, env}, {env, env}};
		int[][] states = {{0, 0}, {0, 0}};
		grid = new CaGrid(states, envGrid, 2, 2, 100.0);

		windField = new WindField(
			new float[][]{{4f, 4f}, {4f, 4f}},
			new float[][]{{225f, 225f}, {225f, 225f}},
			2, 2);

		float[][] roadProx = {{Float.MAX_VALUE, Float.MAX_VALUE},
			{Float.MAX_VALUE, Float.MAX_VALUE}};
		gridInitResult = new GridInitResult(grid, roadProx);

		resampledBands = buildResampledBands();

		// stubSuccessfulLoad() is NOT called here — individual tests call it
		// only when they need a successful grid load. Tests that intentionally
		// skip the load (null-grid, error-path tests) must not have those stubs
		// active or Mockito strict mode flags them as unnecessary.
	}

	// -------------------------------------------------------------------------
	// refreshSession / initialise
	// -------------------------------------------------------------------------

	@Test
	void refreshSession_loadsGridAndSetsModePreFire_whenNoCvPerimeter() throws Exception {
		stubSuccessfulLoad();
		when(cvApiClient.fetchLatestFirePerimeter()).thenReturn(Optional.empty());

		facade.refreshSession();

		assertThat(facade.getMode()).isEqualTo(SimulationModeEnum.PRE_FIRE);
		assertThat(facade.getBaseGrid()).isNotNull();
	}

	@Test
	void refreshSession_setsModeActiveFire_whenCvPerimeterPresent() throws Exception {
		stubSuccessfulLoad();
		FirePerimeterData perimeter = new FirePerimeterData(
			"{}", Collections.emptyList(), Collections.emptyList(),
			Collections.emptyMap(), Instant.now());
		when(cvApiClient.fetchLatestFirePerimeter()).thenReturn(Optional.of(perimeter));

		facade.refreshSession();

		assertThat(facade.getMode()).isEqualTo(SimulationModeEnum.ACTIVE_FIRE);
	}

	@Test
	void refreshSession_logsWarnAndDoesNotThrow_whenFuelStateUnavailable() {
		// No stubSuccessfulLoad — fetchLatestFuelState returns empty (Mockito default)
		// loadGridFromSources logs a warning and returns early without throwing.
		// refreshSession catches nothing — it just completes with baseGrid still null.
		facade.refreshSession();

		assertThat(facade.getBaseGrid()).isNull();
	}

	// -------------------------------------------------------------------------
	// getSessionStatus
	// -------------------------------------------------------------------------

	@Test
	void getSessionStatus_returnsGridDimensionsAndBoundingBox() {
		triggerSuccessfulRefresh();
		when(runLogReaderService.readAll()).thenReturn(Collections.emptyList());

		SessionStatusResponseDto status = facade.getSessionStatus();

		assertThat(status.getRows()).isEqualTo(2);
		assertThat(status.getCols()).isEqualTo(2);
		assertThat(status.getCellSizeMetres()).isEqualTo(100.0);
		assertThat(status.getMinX()).isEqualTo(300000.0);
		assertThat(status.getMaxY()).isEqualTo(9900000.0);
	}

	@Test
	void getSessionStatus_throwsIllegalState_whenGridNotInitialised() {
		// Do not call refreshSession — baseGrid stays null
		assertThatThrownBy(() -> facade.getSessionStatus())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("not initialised");
	}

	@Test
	void getSessionStatus_includesPastRunsFromHistory() {
		triggerSuccessfulRefresh();
		RunRecord record = new RunRecord("run-1", SimulationModeEnum.PRE_FIRE,
			Instant.now(), Instant.now(), Map.of(), null, null);
		when(runLogReaderService.readAll()).thenReturn(List.of(record));

		SessionStatusResponseDto status = facade.getSessionStatus();

		assertThat(status.getPastRuns()).hasSize(1);
		assertThat(status.getPastRuns().get(0).getRunId()).isEqualTo("run-1");
		assertThat(status.getPastRuns().get(0).getPhase()).isEqualTo(SimulationModeEnum.PRE_FIRE);
	}

	// -------------------------------------------------------------------------
	// runPhaseOne
	// -------------------------------------------------------------------------

	@Test
	void runPhaseOne_callsEnsemblePipelineAndReturnsResult() {
		triggerSuccessfulRefresh();
		stubPhaseOnePipeline();

		PhaseOneResultResponseDto result = facade.runPhaseOne(new PhaseOneRunRequestDto());

		assertThat(result).isNotNull();
		verify(icBuilder).build(any(), any());
		verify(seedSampler).sample(any(), any(), anyInt(), anyLong());
		verify(ensembleRunner).run(any(), any(), anyList(), anyLong());
		verify(riskMapAssembler).assemble(any(), any(), anyInt());
		verify(runLogWriterService).write(any());
	}

	@Test
	void runPhaseOne_appliesWindSpeedOverride() throws Exception {
		triggerSuccessfulRefresh();
		stubPhaseOnePipeline();

		PhaseOneRunRequestDto req = new PhaseOneRunRequestDto();
		req.setWindSpeedMsOverride(10.0);

		facade.runPhaseOne(req);

		// WindField passed to ensembleRunner should have overridden speed
		ArgumentCaptor<WindField> windCaptor = ArgumentCaptor.forClass(WindField.class);
		verify(ensembleRunner).run(any(), windCaptor.capture(), anyList(), anyLong());
		WindField captured = windCaptor.getValue();
		assertThat(captured.getSpeedMs()[0][0]).isEqualTo(10.0f);
	}

	@Test
	void runPhaseOne_throwsIllegalState_whenGridNotInitialised() {
		assertThatThrownBy(() -> facade.runPhaseOne(new PhaseOneRunRequestDto()))
			.isInstanceOf(IllegalStateException.class);
	}

	// -------------------------------------------------------------------------
	// runPhaseTwo
	// -------------------------------------------------------------------------

	@Test
	void runPhaseTwo_manualIgnition_seedsCellsFromGeoJson() {
		triggerSuccessfulRefresh();
		stubPhaseTwoPipeline();

		Set<Long> fakeCells = Set.of(0L, 1L, 2L);
		doReturn(fakeCells).when(firePerimeterParserService).parse(
			anyString(), anyDouble(), anyDouble(), anyDouble(), anyInt(), anyInt());

		PhaseTwoRunRequestDto req = new PhaseTwoRunRequestDto();
		req.setManualIgnition(true);
		req.setManualIgnitionPolygonGeoJson("{\"type\":\"Polygon\"}");
		req.setSimulationHours(1);

		facade.runPhaseTwo(req);

		verify(firePerimeterParserService).parse(eq("{\"type\":\"Polygon\"}"),
			anyDouble(), anyDouble(), anyDouble(), anyInt(), anyInt());
		verify(suppressedZoneRegistry).clear();
		verify(caSpreadEngine).run(any(), any(), any(), anyInt());
	}

	@Test
	void runPhaseTwo_cvNotDisabled_fetchesCvPerimeter() {
		triggerSuccessfulRefresh();
		stubPhaseTwoPipeline();

		FirePerimeterData perimeter = new FirePerimeterData(
			"{\"type\":\"Polygon\"}", Collections.emptyList(), Collections.emptyList(),
			Collections.emptyMap(), Instant.now());
		when(cvApiClient.fetchLatestFirePerimeter()).thenReturn(Optional.of(perimeter));
		doReturn(Set.of(0L)).when(firePerimeterParserService).parse(
			anyString(), anyDouble(), anyDouble(), anyDouble(), anyInt(), anyInt());

		PhaseTwoRunRequestDto req = new PhaseTwoRunRequestDto();
		req.setSimulationHours(1);

		facade.runPhaseTwo(req);

		verify(cvApiClient, atLeastOnce()).fetchLatestFirePerimeter();
	}

	@Test
	void runPhaseTwo_generationsCalculatedCorrectly() {
		triggerSuccessfulRefresh();
		stubPhaseTwoPipeline();
		when(simulationConfig.getTimeStepMinutes()).thenReturn(5);

		PhaseTwoRunRequestDto req = new PhaseTwoRunRequestDto();
		req.setSimulationHours(2);
		req.setCvDisabled(true);

		facade.runPhaseTwo(req);

		// 2h * 60min / 5min = 24 generations
		ArgumentCaptor<Integer> genCaptor = ArgumentCaptor.forClass(Integer.class);
		verify(caSpreadEngine).run(any(), any(), any(), genCaptor.capture());
		assertThat(genCaptor.getValue()).isEqualTo(24);
	}

	@Test
	void runPhaseTwo_persistsRunRecord() {
		triggerSuccessfulRefresh();
		stubPhaseTwoPipeline();

		PhaseTwoRunRequestDto req = new PhaseTwoRunRequestDto();
		req.setSimulationHours(1);
		req.setCvDisabled(true);

		facade.runPhaseTwo(req);

		ArgumentCaptor<RunRecord> recordCaptor = ArgumentCaptor.forClass(RunRecord.class);
		verify(runLogWriterService).write(recordCaptor.capture());
		assertThat(recordCaptor.getValue().getPhase()).isEqualTo(SimulationModeEnum.ACTIVE_FIRE);
	}

	// -------------------------------------------------------------------------
	// applyCorrection
	// -------------------------------------------------------------------------

	@Test
	void applyCorrection_throwsIllegalState_whenNoActiveFireGrid() {
		triggerSuccessfulRefresh();

		CvCorrectionRequestDto req = new CvCorrectionRequestDto();
		assertThatThrownBy(() -> facade.applyCorrection(req))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("No active Phase 2");
	}

	@Test
	void applyCorrection_delegatesToInjectorWithParsedIds() {
		triggerSuccessfulRefresh();
		stubPhaseTwoPipeline();

		// First start a Phase 2 run so activeFireGrid is set
		PhaseTwoRunRequestDto p2req = new PhaseTwoRunRequestDto();
		p2req.setSimulationHours(1);
		p2req.setCvDisabled(true);
		facade.runPhaseTwo(p2req);

		CvCorrectionRequestDto correction = new CvCorrectionRequestDto();
		correction.setSuppressedZoneCellIds(List.of("5", "10"));
		correction.setUpdatedMoistureValues(Map.of("3", 0.1f));

		facade.applyCorrection(correction);

		ArgumentCaptor<FirePerimeterData> dataCaptor =
			ArgumentCaptor.forClass(FirePerimeterData.class);
		verify(cvStateInjectorService).inject(any(), dataCaptor.capture());
		assertThat(dataCaptor.getValue().getSuppressedZoneCellIndices())
			.containsExactlyInAnyOrder(5L, 10L);
		assertThat(dataCaptor.getValue().getUpdatedMoistureValues())
			.containsEntry(3L, 0.1f);
	}

	@Test
	void applyCorrection_skipsUnparseableCellIds() {
		triggerSuccessfulRefresh();
		stubPhaseTwoPipeline();

		PhaseTwoRunRequestDto p2req = new PhaseTwoRunRequestDto();
		p2req.setSimulationHours(1);
		p2req.setCvDisabled(true);
		facade.runPhaseTwo(p2req);

		CvCorrectionRequestDto correction = new CvCorrectionRequestDto();
		correction.setSuppressedZoneCellIds(List.of("5", "not-a-number", "10"));

		// Must not throw — bad ids are logged and skipped
		facade.applyCorrection(correction);

		ArgumentCaptor<FirePerimeterData> captor = ArgumentCaptor.forClass(FirePerimeterData.class);
		verify(cvStateInjectorService).inject(any(), captor.capture());
		assertThat(captor.getValue().getSuppressedZoneCellIndices()).containsExactlyInAnyOrder(5L, 10L);
	}

	// -------------------------------------------------------------------------
	// History delegation
	// -------------------------------------------------------------------------

	@Test
	void getAllRuns_delegatesToReader() {
		RunRecord r = new RunRecord("id", SimulationModeEnum.PRE_FIRE,
			Instant.now(), Instant.now(), Map.of(), null);
		when(runLogReaderService.readAll()).thenReturn(List.of(r));

		assertThat(facade.getAllRuns()).hasSize(1);
	}

	@Test
	void getRunById_returnsNullWhenNotFound() {
		// Unstubbed mock returns null by default — no stub needed
		assertThat(facade.getRunById("missing")).isNull();
	}

	// -------------------------------------------------------------------------
	// Setup helpers
	// -------------------------------------------------------------------------

	private void stubSuccessfulLoad() throws Exception {
		Path fakePath = Path.of("./data/cv.tif");
		when(cvApiClient.fetchLatestFuelState()).thenReturn(Optional.of(fakePath));

		GridBands nativeBands = buildNativeBands();
		EsaBands nativeEsa = buildNativeEsa();
		when(geoTiffBandReaderService.read(any())).thenReturn(nativeBands);
		when(geoTiffBandReaderService.readEsa(any())).thenReturn(nativeEsa);
		// readFuelRisk — only called when fuelRiskPath is set; stubbed defensively
		FuelRiskBands nativeRisk = new FuelRiskBands(
			new byte[][]{{1, 2}, {2, 3}}, 2, 2, 10.0,
			300000.0, 9800000.0, 300200.0, 9900000.0);
		when(geoTiffBandReaderService.readFuelRisk(any())).thenReturn(nativeRisk);
		when(osmRoadLoaderService.load()).thenReturn(new RoadLayer(Collections.emptyList()));
		when(rasterResamplerService.resample(any())).thenReturn(resampledBands);
		when(rasterResamplerService.resampleEsa(any())).thenReturn(new int[][]{{10, 30}, {30, 30}});
		when(rasterResamplerService.resampleFuelRisk(any()))
			.thenReturn(new byte[][]{{1, 2}, {2, 3}});
		// 4-argument build signature (added with fuel risk support)
		when(gridInitialiserService.build(any(), any(), any(), any())).thenReturn(gridInitResult);
		when(windFieldLoaderService.load(anyInt(), anyInt())).thenReturn(windField);
	}

	private void triggerSuccessfulRefresh() {
		try {
			stubSuccessfulLoad();
		} catch (Exception e) {
			throw new RuntimeException("stubSuccessfulLoad failed in test setup", e);
		}
		when(cvApiClient.fetchLatestFirePerimeter()).thenReturn(Optional.empty());
		facade.refreshSession();
	}

	private void stubPhaseOnePipeline() {
		when(simulationConfig.getMonteCarloRuns()).thenReturn(10);
		when(simulationConfig.getTimeStepMinutes()).thenReturn(5);
		when(simulationConfig.getPhase1HorizonHours()).thenReturn(24);
		when(simulationConfig.getCellSizeMetres()).thenReturn(100.0);
		when(icBuilder.build(any(), any())).thenReturn(new float[]{0.5f, 0.5f, 0.5f, 0.5f});
		when(seedSampler.sample(any(), any(), anyInt(), anyLong()))
			.thenReturn(List.of(0L, 1L));
		BurnFrequencyAccumulator acc = new BurnFrequencyAccumulator(2, 2);
		when(ensembleRunner.run(any(), any(), anyList(), anyLong())).thenReturn(acc);
		PhaseOneResult result = new PhaseOneResult(
			new float[]{0.1f, 0.2f, 0.3f, 0.4f},
			new float[]{0.4f, 0.3f, 0.2f, 0.1f},
			2, 2);
		when(riskMapAssembler.assemble(any(), any(), anyInt())).thenReturn(result);
		// Analytics stub — returns a minimal all-null analytics object
		RunAnalytics analytics = new RunAnalytics(1, 0.01, List.of(0L), "GRASSLAND",
			null, null, null, null);
		when(analyticsService.summarisePhaseOne(any(), any(), any(), anyInt()))
			.thenReturn(analytics);
		PhaseOneResultResponseDto dto = new PhaseOneResultResponseDto(
			"run-1",
			new float[]{0.1f, 0.2f, 0.3f, 0.4f},
			new float[]{0.4f, 0.3f, 0.2f, 0.1f},
			new int[]{1, 1, 1, 1},
			2, 2,
			analytics);
		// Updated signature: assemblePhaseOne now accepts analytics as fifth argument
		when(resultAssemblerService.assemblePhaseOne(any(), any(), any(), any(), any()))
			.thenReturn(dto);
	}

	private void stubPhaseTwoPipeline() {
		when(simulationConfig.getTimeStepMinutes()).thenReturn(5);
		when(simulationConfig.getCellSizeMetres()).thenReturn(100.0);
		when(simulationConfig.getPhase1HorizonHours()).thenReturn(24);
		when(simulationConfig.getMonteCarloRuns()).thenReturn(10);
		when(caSpreadEngine.run(any(), any(), any(), anyInt()))
			.thenReturn(Collections.emptyList());
		RunAnalytics analytics = new RunAnalytics(null, null, null, null,
			0.0, null, 0, 0);
		when(analyticsService.summarisePhaseTwo(anyList(), any(), any()))
			.thenReturn(analytics);
		PhaseTwoResultResponseDto dto = new PhaseTwoResultResponseDto(
			"run-2", Collections.emptyList(), analytics);
		// Updated signature: assemblePhaseTwo now accepts analytics as fourth argument
		when(resultAssemblerService.assemblePhaseTwo(any(), any(), anyList(), any()))
			.thenReturn(dto);
	}

	// --- Minimal fixture builders ---

	private GridBands buildNativeBands() {
		float[][] data = {{0.5f, 0.5f}, {0.5f, 0.5f}};
		return new GridBands(data, data, data, data, data, 2, 2, 10.0,
			300000.0, 9800000.0, 300200.0, 9900000.0);
	}

	private GridBands buildResampledBands() {
		float[][] data = {{0.5f, 0.5f}, {0.5f, 0.5f}};
		return new GridBands(data, data, data, data, data, 2, 2, 100.0,
			300000.0, 9800000.0, 300200.0, 9900000.0);
	}

	private EsaBands buildNativeEsa() {
		return new EsaBands(new int[][]{{30, 30}, {30, 30}}, 2, 2, 10.0,
			300000.0, 9800000.0, 300200.0, 9900000.0);
	}
}