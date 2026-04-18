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
import com.victorkithinji.wrap.wrapca.dto.response.RunSummaryResponseDto;
import com.victorkithinji.wrap.wrapca.dto.response.SessionStatusResponseDto;
import com.victorkithinji.wrap.wrapca.grid.CaGrid;
import com.victorkithinji.wrap.wrapca.grid.CellStateEnum;
import com.victorkithinji.wrap.wrapca.grid.GridInitResult;
import com.victorkithinji.wrap.wrapca.grid.GridInitialiserService;
import com.victorkithinji.wrap.wrapca.history.RunLogReaderService;
import com.victorkithinji.wrap.wrapca.history.RunLogWriterService;
import com.victorkithinji.wrap.wrapca.history.RunRecord;

import com.victorkithinji.wrap.wrapca.ingestion.EsaBands;
import com.victorkithinji.wrap.wrapca.ingestion.GeoTiffBandReaderService;
import com.victorkithinji.wrap.wrapca.ingestion.GridBands;
import com.victorkithinji.wrap.wrapca.ingestion.OsmRoadLoaderService;
import com.victorkithinji.wrap.wrapca.ingestion.RasterResamplerService;
import com.victorkithinji.wrap.wrapca.ingestion.RoadLayer;
import com.victorkithinji.wrap.wrapca.ingestion.WindField;
import com.victorkithinji.wrap.wrapca.ingestion.WindFieldLoaderService;
import com.victorkithinji.wrap.wrapca.ingestion.FirePerimeterParserService;
import com.victorkithinji.wrap.wrapca.montecarlo.BurnFrequencyAccumulator;
import com.victorkithinji.wrap.wrapca.montecarlo.IgnitionLikelihoodIndexBuilder;
import com.victorkithinji.wrap.wrapca.montecarlo.IgnitionSeedSampler;
import com.victorkithinji.wrap.wrapca.montecarlo.MonteCarloEnsembleRunner;
import com.victorkithinji.wrap.wrapca.montecarlo.PhaseOneResult;
import com.victorkithinji.wrap.wrapca.montecarlo.RiskMapAssembler;
import com.victorkithinji.wrap.wrapca.output.SimulationResultAssemblerService;
import com.victorkithinji.wrap.wrapca.simulation.CaSpreadEngine;
import com.victorkithinji.wrap.wrapca.simulation.SimulationStepResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Central session orchestrator for the WRaP CA engine.
 * <p>
 * Responsibilities:
 * - Startup: load geospatial data → build grid → detect mode
 * - Scheduled refresh every 3 hours (same pipeline)
 * - Phase 1: drive the Monte Carlo ensemble
 * - Phase 2: drive active-fire spread
 * - CV correction: delegate to CvStateInjectorService on the active grid
 * - History: delegate to RunLogReaderService
 * <p>
 * Thread safety: methods that write to shared volatile fields (refreshSession,
 * runPhaseTwo, applyCorrection) are synchronized. Phase 1 reads baseGrid
 * without locking because Monte Carlo tasks each get their own deepCopy.
 */
@Slf4j
@Service
public class WrapSessionFacade {

	private static final long MASTER_SEED = 42L;

	@Value("${wrap.data.esa-path}")
	private String esaPath;

	private final SimulationConfig simulationConfig;
	private final CvApiClient cvApiClient;
	private final GeoTiffBandReaderService geoTiffBandReaderService;
	private final OsmRoadLoaderService osmRoadLoaderService;
	private final RasterResamplerService rasterResamplerService;
	private final GridInitialiserService gridInitialiserService;
	private final WindFieldLoaderService windFieldLoaderService;
	private final FirePerimeterParserService firePerimeterParserService;
	private final IgnitionLikelihoodIndexBuilder icBuilder;
	private final IgnitionSeedSampler seedSampler;
	private final MonteCarloEnsembleRunner ensembleRunner;
	private final RiskMapAssembler riskMapAssembler;
	private final CaSpreadEngine caSpreadEngine;
	private final CvStateInjectorService cvStateInjectorService;
	private final SuppressedZoneRegistry suppressedZoneRegistry;
	private final SimulationResultAssemblerService resultAssemblerService;
	private final RunLogWriterService runLogWriterService;
	private final RunLogReaderService runLogReaderService;

	// --- Live session state (all written under synchronization except baseGrid reads) ---
	private volatile CaGrid baseGrid;
	private volatile WindField windField;
	private volatile GridInitResult gridInitResult;
	// Bounding box stored from resampled GridBands; CaGrid does not carry spatial metadata
	private volatile double minX;
	private volatile double minY;
	private volatile double maxX;
	private volatile double maxY;
	private volatile SimulationModeEnum mode = SimulationModeEnum.PRE_FIRE;
	// Active Phase 2 grid — separate from baseGrid so Phase 1 can still run in parallel
	private volatile CaGrid activeFireGrid;

	public WrapSessionFacade(
		SimulationConfig simulationConfig,
		CvApiClient cvApiClient,
		GeoTiffBandReaderService geoTiffBandReaderService,
		OsmRoadLoaderService osmRoadLoaderService,
		RasterResamplerService rasterResamplerService,
		GridInitialiserService gridInitialiserService,
		WindFieldLoaderService windFieldLoaderService,
		FirePerimeterParserService firePerimeterParserService,
		IgnitionLikelihoodIndexBuilder icBuilder,
		IgnitionSeedSampler seedSampler,
		MonteCarloEnsembleRunner ensembleRunner,
		RiskMapAssembler riskMapAssembler,
		CaSpreadEngine caSpreadEngine,
		CvStateInjectorService cvStateInjectorService,
		SuppressedZoneRegistry suppressedZoneRegistry,
		SimulationResultAssemblerService resultAssemblerService,
		RunLogWriterService runLogWriterService,
		RunLogReaderService runLogReaderService) {
		this.simulationConfig = simulationConfig;
		this.cvApiClient = cvApiClient;
		this.geoTiffBandReaderService = geoTiffBandReaderService;
		this.osmRoadLoaderService = osmRoadLoaderService;
		this.rasterResamplerService = rasterResamplerService;
		this.gridInitialiserService = gridInitialiserService;
		this.windFieldLoaderService = windFieldLoaderService;
		this.firePerimeterParserService = firePerimeterParserService;
		this.icBuilder = icBuilder;
		this.seedSampler = seedSampler;
		this.ensembleRunner = ensembleRunner;
		this.riskMapAssembler = riskMapAssembler;
		this.caSpreadEngine = caSpreadEngine;
		this.cvStateInjectorService = cvStateInjectorService;
		this.suppressedZoneRegistry = suppressedZoneRegistry;
		this.resultAssemblerService = resultAssemblerService;
		this.runLogWriterService = runLogWriterService;
		this.runLogReaderService = runLogReaderService;
	}

	// -------------------------------------------------------------------------
	// Startup and scheduled refresh
	// -------------------------------------------------------------------------

	@PostConstruct
	public void initialise() {
		log.info("WrapSessionFacade: starting initialisation sequence");
		refreshSession();
	}

	/**
	 * Full reload pipeline. Invoked at startup, every 3 hours, and on
	 * POST /api/session/refresh.
	 */
	@Scheduled(fixedDelayString = "PT3H")
	public synchronized void refreshSession() {
		log.info("WrapSessionFacade: refreshing session");
		try {
			loadGridFromSources();
			if (baseGrid == null) {
				log.warn("WrapSessionFacade: session refresh completed but grid is not ready — " +
					"endpoints will return 503 until data is available.");
				return;
			}
			pollCvForMode();
			log.info("WrapSessionFacade: session ready — mode={}, grid={}x{}",
				mode, baseGrid.rows, baseGrid.cols);
		} catch (Exception e) {
			log.error("WrapSessionFacade: session refresh failed — {}", e.getMessage(), e);
		}
	}

	// -------------------------------------------------------------------------
	// Grid loading pipeline
	// -------------------------------------------------------------------------

	private void loadGridFromSources() throws Exception {
		// 1. CV fuel-state GeoTIFF (cached daily via IngestionCacheService inside CvApiClient)
		Optional<Path> fuelStatePath = cvApiClient.fetchLatestFuelState();
		if (fuelStatePath.isEmpty()) {
			log.warn("WrapSessionFacade: no CV fuel-state GeoTIFF available — " +
				"grid will not be initialised this cycle. " +
				"In stub mode: place a GeoTIFF at wrap.cv.geotiff-path and set " +
				"wrap.cv.stub-mode=false, or wait for CV connectivity.");
			return;
		}

		// 2. Read native-resolution bands from the 11-band CV tiff
		GridBands nativeBands = geoTiffBandReaderService.read(fuelStatePath.get());

		// 3. Read ESA WorldCover at native resolution
		EsaBands nativeEsa = geoTiffBandReaderService.readEsa(Path.of(esaPath));

		// 4. Load road layer — empty RoadLayer is fine, logged by service
		RoadLayer roadLayer = osmRoadLoaderService.load();

		// 5. Resample both rasters to CA target resolution
		GridBands resampledBands = rasterResamplerService.resample(nativeBands);
		int[][] resampledEsa = rasterResamplerService.resampleEsa(nativeEsa);

		// 6. Capture bounding box from resampled bands before passing to grid builder
		minX = resampledBands.getMinX();
		minY = resampledBands.getMinY();
		maxX = resampledBands.getMaxX();
		maxY = resampledBands.getMaxY();

		// 7. Build grid
		gridInitResult = gridInitialiserService.build(resampledBands, resampledEsa, roadLayer);
		baseGrid = gridInitResult.getGrid();

		// 8. Load wind field dimensioned to the target grid
		windField = windFieldLoaderService.load(baseGrid.rows, baseGrid.cols);
	}

	// -------------------------------------------------------------------------
	// Mode detection
	// -------------------------------------------------------------------------

	private void pollCvForMode() {
		Optional<FirePerimeterData> perimeter = cvApiClient.fetchLatestFirePerimeter();
		if (perimeter.isPresent()) {
			mode = SimulationModeEnum.ACTIVE_FIRE;
			log.info("WrapSessionFacade: CV perimeter present — mode=ACTIVE_FIRE");
		} else {
			mode = SimulationModeEnum.PRE_FIRE;
			log.info("WrapSessionFacade: no CV perimeter — mode=PRE_FIRE");
		}
	}

	// -------------------------------------------------------------------------
	// Session status
	// -------------------------------------------------------------------------

	public SessionStatusResponseDto getSessionStatus() {
		assertGridReady();
		List<RunRecord> records = runLogReaderService.readAll();
		List<RunSummaryResponseDto> pastRuns = records.stream()
			.map(r -> new RunSummaryResponseDto(
				r.getRunId(),
				SimulationModeEnum.valueOf(r.getPhase().name()),
				r.getStartedAt().toString(),
				r.getCompletedAt().toString()))
			.collect(Collectors.toList());

		return new SessionStatusResponseDto(
			mode,
			baseGrid.rows,
			baseGrid.cols,
			baseGrid.cellSizeMetres,
			minX, minY, maxX, maxY,
			pastRuns);
	}

	// -------------------------------------------------------------------------
	// Phase 1 — Monte Carlo ensemble
	// -------------------------------------------------------------------------

	public PhaseOneResultResponseDto runPhaseOne(PhaseOneRunRequestDto request) {
		assertGridReady();
		String runId = UUID.randomUUID().toString();
		Instant started = Instant.now();
		log.info("Phase 1 starting — runId={}", runId);

		WindField effectiveWind = applyWindOverrides(request);

		float[] ic = icBuilder.build(baseGrid, gridInitResult.getRoadProximityMetres());

		List<Long> seeds = seedSampler.sample(
			baseGrid, ic, simulationConfig.getMonteCarloRuns(), MASTER_SEED);

		BurnFrequencyAccumulator accumulator =
			ensembleRunner.run(baseGrid, effectiveWind, seeds, MASTER_SEED);

		PhaseOneResult result =
			riskMapAssembler.assemble(accumulator, ic, simulationConfig.getMonteCarloRuns());

		Instant completed = Instant.now();
		persistRunRecord(runId, SimulationModeEnum.PRE_FIRE, started, completed,
			buildParamsSnapshot(request));

		log.info("Phase 1 complete — runId={}, elapsed={}ms",
			runId, elapsed(started, completed));

		return resultAssemblerService.assemblePhaseOne(
			runId, baseGrid,
			result.getDamagePotential(),
			result.getIgnitionLikelihood());
	}

	// -------------------------------------------------------------------------
	// Phase 2 — Active fire spread
	// -------------------------------------------------------------------------

	public synchronized PhaseTwoResultResponseDto runPhaseTwo(PhaseTwoRunRequestDto request) {
		assertGridReady();
		String runId = UUID.randomUUID().toString();
		Instant started = Instant.now();
		log.info("Phase 2 starting — runId={}, manualIgnition={}, cvDisabled={}",
			runId, request.isManualIgnition(), request.isCvDisabled());

		activeFireGrid = baseGrid.deepCopy();
		suppressedZoneRegistry.clear();

		seedActiveFire(request);

		int generations = (request.getSimulationHours() * 60)
			/ simulationConfig.getTimeStepMinutes();

		List<SimulationStepResult> steps = caSpreadEngine.run(
			activeFireGrid, windField, suppressedZoneRegistry, generations);

		Instant completed = Instant.now();
		persistRunRecord(runId, SimulationModeEnum.ACTIVE_FIRE, started, completed,
			buildParamsSnapshot(request));

		log.info("Phase 2 complete — runId={}, generations={}, elapsed={}ms",
			runId, steps.size(), elapsed(started, completed));

		return resultAssemblerService.assemblePhaseTwo(runId, activeFireGrid, steps);
	}

	private void seedActiveFire(PhaseTwoRunRequestDto request) {
		if (request.isManualIgnition() && request.getManualIgnitionPolygonGeoJson() != null) {
			Set<Long> cells = parseGeoJsonToGrid(request.getManualIgnitionPolygonGeoJson());
			applyIgnitionCells(cells, "manual ignition polygon");
		} else if (!request.isCvDisabled()) {
			cvApiClient.fetchLatestFirePerimeter().ifPresent(data -> {
				if (data.getPerimeterGeoJson() != null) {
					Set<Long> cells = parseGeoJsonToGrid(data.getPerimeterGeoJson());
					applyIgnitionCells(cells, "CV perimeter");
				}
			});
		} else {
			log.warn("Phase 2: cvDisabled=true with no manual polygon — no ignition cells set");
		}
	}

	private Set<Long> parseGeoJsonToGrid(String geoJson) {
		try {
			return firePerimeterParserService.parse(
				geoJson, minX, minY,
				activeFireGrid.cellSizeMetres,
				activeFireGrid.rows,
				activeFireGrid.cols);
		} catch (java.io.IOException e) {
			log.error("Failed to parse fire perimeter GeoJSON — no ignition cells will be set: {}",
				e.getMessage(), e);
			return java.util.Collections.emptySet();
		}
	}

	private void applyIgnitionCells(Set<Long> cells, String source) {
		int applied = 0;
		for (long idx : cells) {
			int row = activeFireGrid.decodeRow(idx);
			int col = activeFireGrid.decodeCol(idx);
			if (activeFireGrid.inBounds(row, col)) {
				activeFireGrid.setState(row, col, CellStateEnum.BURNING);
				applied++;
			}
		}
		log.info("Phase 2: set {} BURNING cells from {}", applied, source);
	}

	// -------------------------------------------------------------------------
	// CV correction (Phase 2 only)
	// -------------------------------------------------------------------------

	public synchronized void applyCorrection(CvCorrectionRequestDto request) {
		if (activeFireGrid == null) {
			throw new IllegalStateException(
				"No active Phase 2 grid. POST /api/simulation/phase-two/run first.");
		}
		List<Long> suppressedIds = parseCellIds(request.getSuppressedZoneCellIds());
		Map<Long, Float> moistureMap = parseMoistureMap(request.getUpdatedMoistureValues());

		FirePerimeterData correction = new FirePerimeterData(
			request.getObservedPerimeterGeoJson(),
			Collections.emptyList(),
			suppressedIds,
			moistureMap,
			Instant.now());

		cvStateInjectorService.inject(activeFireGrid, correction);
		log.info("CV correction applied — suppressed={}, moistureUpdates={}",
			suppressedIds.size(), moistureMap.size());
	}

	// -------------------------------------------------------------------------
	// History delegation
	// -------------------------------------------------------------------------

	public List<RunRecord> getAllRuns() {
		return runLogReaderService.readAll();
	}

	public RunRecord getRunById(String runId) {
		return runLogReaderService.findById(runId);
	}

	// -------------------------------------------------------------------------
	// Internal helpers
	// -------------------------------------------------------------------------

	private void assertGridReady() {
		if (baseGrid == null) {
			throw new IllegalStateException(
				"Grid not initialised — startup failed. Check application logs.");
		}
	}

	private WindField applyWindOverrides(PhaseOneRunRequestDto request) {
		if (request == null) return windField;
		Double speedOverride = request.getWindSpeedMsOverride();
		Double dirOverride = request.getWindDirectionDegOverride();
		if (speedOverride == null && dirOverride == null) return windField;

		int rows = windField.getRows();
		int cols = windField.getCols();
		float[][] speed = new float[rows][cols];
		float[][] dir = new float[rows][cols];
		for (int r = 0; r < rows; r++) {
			for (int c = 0; c < cols; c++) {
				speed[r][c] = speedOverride != null
					? speedOverride.floatValue()
					: windField.getSpeedMs()[r][c];
				dir[r][c] = dirOverride != null
					? dirOverride.floatValue()
					: windField.getDirectionDeg()[r][c];
			}
		}
		return new WindField(speed, dir, rows, cols);
	}

	private List<Long> parseCellIds(List<String> ids) {
		if (ids == null || ids.isEmpty()) return Collections.emptyList();
		List<Long> result = new ArrayList<>(ids.size());
		for (String id : ids) {
			try {
				result.add(Long.parseLong(id.trim()));
			} catch (NumberFormatException e) {
				log.warn("Skipping unparseable cell id: '{}'", id);
			}
		}
		return result;
	}

	private Map<Long, Float> parseMoistureMap(Map<String, Float> raw) {
		if (raw == null || raw.isEmpty()) return Collections.emptyMap();
		Map<Long, Float> result = new HashMap<>(raw.size());
		raw.forEach((k, v) -> {
			try {
				result.put(Long.parseLong(k.trim()), v);
			} catch (NumberFormatException e) {
				log.warn("Skipping unparseable moisture key: '{}'", k);
			}
		});
		return result;
	}

	private void persistRunRecord(String runId, SimulationModeEnum phase,
								  Instant started, Instant completed, Map<String, Object> params) {
		runLogWriterService.write(
			new RunRecord(runId, phase, started, completed, params, null));
	}

	private Map<String, Object> buildParamsSnapshot(Object request) {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("cellSizeMetres", simulationConfig.getCellSizeMetres());
		params.put("timeStepMinutes", simulationConfig.getTimeStepMinutes());
		params.put("monteCarloRuns", simulationConfig.getMonteCarloRuns());
		params.put("phase1HorizonHours", simulationConfig.getPhase1HorizonHours());
		if (request instanceof PhaseOneRunRequestDto p1) {
			if (p1.getWindSpeedMsOverride() != null)
				params.put("windSpeedMsOverride", p1.getWindSpeedMsOverride());
			if (p1.getWindDirectionDegOverride() != null)
				params.put("windDirectionDegOverride", p1.getWindDirectionDegOverride());
		} else if (request instanceof PhaseTwoRunRequestDto p2) {
			params.put("simulationHours", p2.getSimulationHours());
			params.put("manualIgnition", p2.isManualIgnition());
			params.put("cvDisabled", p2.isCvDisabled());
		}
		return params;
	}

	private long elapsed(Instant start, Instant end) {
		return end.toEpochMilli() - start.toEpochMilli();
	}

	// Package-visible for tests
	SimulationModeEnum getMode() {
		return mode;
	}

	CaGrid getBaseGrid() {
		return baseGrid;
	}

	CaGrid getActiveFireGrid() {
		return activeFireGrid;
	}
}