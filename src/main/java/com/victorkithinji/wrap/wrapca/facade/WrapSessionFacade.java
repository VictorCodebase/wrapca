package com.victorkithinji.wrap.wrapca.facade;

import com.victorkithinji.wrap.wrapca.config.SimulationConfig;
import com.victorkithinji.wrap.wrapca.correction.CvStateInjectorService;
import com.victorkithinji.wrap.wrapca.correction.SuppressedZoneRegistry;
import com.victorkithinji.wrap.wrapca.cvintegration.CvApiClient;
import com.victorkithinji.wrap.wrapca.cvintegration.FirePerimeterData;
import com.victorkithinji.wrap.wrapca.dto.SimulationModeEnum;
import com.victorkithinji.wrap.wrapca.dto.request.CvCorrectionRequestDto;
import com.victorkithinji.wrap.wrapca.dto.request.ModeOverrideRequestDto;
import com.victorkithinji.wrap.wrapca.dto.request.PhaseOneRunRequestDto;
import com.victorkithinji.wrap.wrapca.dto.request.PhaseTwoRunRequestDto;
import com.victorkithinji.wrap.wrapca.dto.response.GridEnvironmentResponseDto;
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
import com.victorkithinji.wrap.wrapca.ingestion.FuelRiskBands;
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
import com.victorkithinji.wrap.wrapca.output.RunAnalytics;
import com.victorkithinji.wrap.wrapca.output.RunAnalyticsService;
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

	/**
	 * Optional — system starts normally when absent; fuelRiskValues will be all zeros.
	 */
	@Value("${wrap.data.fuel-risk-path:}")
	private String fuelRiskPath;

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
	private final RunAnalyticsService analyticsService;
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
	// Elevation and slope captured from resampled GridBands at init time.
	// CaGrid stores slopeRadians in CellEnvironment but not elevationMetres directly
	// in a flat array — we hold flat arrays here for O(1) response assembly.
	private volatile float[] cachedElevationMetres;
	private volatile float[] cachedSlopeDegrees;
	// Flat row-major fuel risk codes from CV, captured at grid init time.
	// Values 1–3; 0 = NoData or file absent. Never null after first successful load.
	private volatile byte[] cachedFuelRiskFlat;

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
		RunAnalyticsService analyticsService,
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
		this.analyticsService = analyticsService;
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
		long t0 = System.currentTimeMillis();

		// 1. CV fuel-state GeoTIFF (cached daily via IngestionCacheService inside CvApiClient)
		Optional<Path> fuelStatePath = cvApiClient.fetchLatestFuelState();
		if (fuelStatePath.isEmpty()) {
			log.warn("WrapSessionFacade: no CV fuel-state GeoTIFF available — " +
				"grid will not be initialised this cycle. " +
				"In stub mode: place a GeoTIFF at wrap.cv.geotiff-path and set " +
				"wrap.cv.stub-mode=false, or wait for CV connectivity.");
			return;
		}
		log.debug("loadGridFromSources: fuel-state resolved in {}ms",
			System.currentTimeMillis() - t0);

		// 2. Read native-resolution bands from the 11-band CV tiff
		long t1 = System.currentTimeMillis();
		GridBands nativeBands = geoTiffBandReaderService.read(fuelStatePath.get());
		log.debug("loadGridFromSources: CV GeoTIFF read in {}ms — native {}x{}",
			System.currentTimeMillis() - t1, nativeBands.getRows(), nativeBands.getCols());

		// 3. Read ESA WorldCover at native resolution
		t1 = System.currentTimeMillis();
		EsaBands nativeEsa = geoTiffBandReaderService.readEsa(Path.of(esaPath));
		log.debug("loadGridFromSources: ESA GeoTIFF read in {}ms — native {}x{}",
			System.currentTimeMillis() - t1, nativeEsa.getRows(), nativeEsa.getCols());

		// 4. Load road layer — empty RoadLayer is fine, logged by service
		t1 = System.currentTimeMillis();
		RoadLayer roadLayer = osmRoadLoaderService.load();
		log.debug("loadGridFromSources: road layer loaded in {}ms — {} segments",
			System.currentTimeMillis() - t1, roadLayer.getSegments().size());

		// 5. Resample both rasters to CA target resolution
		t1 = System.currentTimeMillis();
		GridBands resampledBands = rasterResamplerService.resample(nativeBands);
		int[][] resampledEsa = rasterResamplerService.resampleEsa(nativeEsa);
		log.debug("loadGridFromSources: resampling complete in {}ms — target {}x{}",
			System.currentTimeMillis() - t1,
			resampledBands.getRows(), resampledBands.getCols());

		// 5b. Load and resample fuel risk map (optional — startup continues if absent)
		int dstRows = resampledBands.getRows();
		int dstCols = resampledBands.getCols();
		byte[][] fuelRiskCodes = loadAndResampleFuelRisk(dstRows, dstCols);

		// 6. Capture bounding box from resampled bands before passing to grid builder
		minX = resampledBands.getMinX();
		minY = resampledBands.getMinY();
		maxX = resampledBands.getMaxX();
		maxY = resampledBands.getMaxY();

		// Capture elevation and slope as flat row-major arrays for GET /api/session/grid.
		// Done here because CaGrid does not hold a flat elevation array, and
		// resampledBands is discarded after gridInitialiserService.build().
		float[][] elevGrid = resampledBands.getElevationMetres();
		float[][] slopeGrid = resampledBands.getSlopeDegrees();
		float[] elevFlat = new float[dstRows * dstCols];
		float[] slopeFlat = new float[dstRows * dstCols];
		byte[] riskFlat = new byte[dstRows * dstCols];
		for (int r = 0; r < dstRows; r++) {
			for (int c = 0; c < dstCols; c++) {
				int idx = r * dstCols + c;
				elevFlat[idx] = elevGrid[r][c];
				slopeFlat[idx] = slopeGrid[r][c];
				riskFlat[idx] = fuelRiskCodes[r][c];
			}
		}
		cachedElevationMetres = elevFlat;
		cachedSlopeDegrees = slopeFlat;
		cachedFuelRiskFlat = riskFlat;

		// 7. Build grid — four-argument signature includes fuel risk codes
		t1 = System.currentTimeMillis();
		gridInitResult = gridInitialiserService.build(
			resampledBands, resampledEsa, roadLayer, fuelRiskCodes);
		baseGrid = gridInitResult.getGrid();
		log.debug("loadGridFromSources: grid built in {}ms — {}x{} cells",
			System.currentTimeMillis() - t1, baseGrid.rows, baseGrid.cols);

		// 8. Load wind field aligned to the target grid
		windField = windFieldLoaderService.load(baseGrid.rows, baseGrid.cols);

		log.info("loadGridFromSources: complete in {}ms total — grid={}x{} ({} cells), " +
				"cellSize={}m, bounds=X[{},{}] Y[{},{}]",
			System.currentTimeMillis() - t0,
			baseGrid.rows, baseGrid.cols, baseGrid.rows * baseGrid.cols,
			baseGrid.cellSizeMetres,
			(int) minX, (int) maxX, (int) minY, (int) maxY);
	}

	/**
	 * Loads the CV fuel risk GeoTIFF and resamples it to target grid dimensions.
	 * Returns a zero-filled array of the correct size when:
	 * - wrap.data.fuel-risk-path is not set
	 * - the file does not exist
	 * - any read error occurs
	 * The system never fails to start due to a missing fuel risk file.
	 */
	private byte[][] loadAndResampleFuelRisk(int targetRows, int targetCols) {
		byte[][] zeros = new byte[targetRows][targetCols];

		if (fuelRiskPath == null || fuelRiskPath.isBlank()) {
			log.debug("loadAndResampleFuelRisk: wrap.data.fuel-risk-path not set — " +
				"fuelRiskValues will be all zeros");
			return zeros;
		}

		Path path = Path.of(fuelRiskPath);
		if (!java.nio.file.Files.exists(path)) {
			log.warn("loadAndResampleFuelRisk: fuel risk file not found at {} — " +
				"fuelRiskValues will be all zeros", path.toAbsolutePath());
			return zeros;
		}

		try {
			long t = System.currentTimeMillis();
			FuelRiskBands nativeRisk = geoTiffBandReaderService.readFuelRisk(path);

			// Guard: if cellSizeMetres looks like a degree value (< 1.0) the reader
			// did not reproject the envelope. Substitute the known native pixel size
			// in metres (~10m for this CV product) so the resampler produces the
			// correct target dimensions instead of collapsing to 1×1.
			if (nativeRisk.getCellSizeMetres() < 1.0) {
				log.warn("loadAndResampleFuelRisk: cellSizeMetres={} looks like degrees, " +
						"not metres — GeoTiffBandReaderService.readFuelRisk() needs " +
						"envelope reprojection (Group 4 fix pending). " +
						"Substituting 10.0m as native pixel size for resampling.",
					nativeRisk.getCellSizeMetres());
				nativeRisk = new FuelRiskBands(
					nativeRisk.getRiskCodes(),
					nativeRisk.getRows(),
					nativeRisk.getCols(),
					10.0,                         // corrected native pixel size metres
					nativeRisk.getMinX(),
					nativeRisk.getMinY(),
					nativeRisk.getMaxX(),
					nativeRisk.getMaxY());
			}

			byte[][] resampled = rasterResamplerService.resampleFuelRisk(nativeRisk);
			log.debug("loadAndResampleFuelRisk: loaded and resampled in {}ms — " +
					"native {}x{} → target {}x{}",
				System.currentTimeMillis() - t,
				nativeRisk.getRows(), nativeRisk.getCols(),
				resampled.length, resampled[0].length);
			return resampled;
		} catch (Exception e) {
			log.warn("loadAndResampleFuelRisk: failed to read fuel risk file ({}) — " +
				"fuelRiskValues will be all zeros: {}", path, e.getMessage(), e);
			return zeros;
		}
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
			pastRuns,
			cachedFuelRiskFlat);
	}

	// -------------------------------------------------------------------------
	// Phase 1 — Monte Carlo ensemble
	// -------------------------------------------------------------------------

	public PhaseOneResultResponseDto runPhaseOne(PhaseOneRunRequestDto request) {
		assertGridReady();
		String runId = UUID.randomUUID().toString();
		Instant started = Instant.now();
		log.info("Phase 1 starting — runId={}, grid={}x{}, cells={}, runs={}, " +
				"horizonHours={}, stepMinutes={}",
			runId,
			baseGrid.rows, baseGrid.cols,
			baseGrid.rows * baseGrid.cols,
			simulationConfig.getMonteCarloRuns(),
			simulationConfig.getPhase1HorizonHours(),
			simulationConfig.getTimeStepMinutes());

		WindField effectiveWind = applyWindOverrides(request);

		// Stage 1: I(c) weights
		long t0 = System.currentTimeMillis();
		float[] ic = icBuilder.build(baseGrid, gridInitResult.getRoadProximityMetres());
		log.debug("Phase 1 [{}]: I(c) build complete — {}ms", runId,
			System.currentTimeMillis() - t0);

		// Stage 2: seed sampling
		t0 = System.currentTimeMillis();
		List<Long> seeds = seedSampler.sample(
			baseGrid, ic, simulationConfig.getMonteCarloRuns(), MASTER_SEED);
		log.debug("Phase 1 [{}]: seed sampling complete — {} seeds, {}ms", runId,
			seeds.size(), System.currentTimeMillis() - t0);

		// Stage 3: Monte Carlo ensemble — the long stage
		log.info("Phase 1 [{}]: starting ensemble ({} runs, {} generations each)",
			runId,
			simulationConfig.getMonteCarloRuns(),
			(simulationConfig.getPhase1HorizonHours() * 60)
				/ simulationConfig.getTimeStepMinutes());
		t0 = System.currentTimeMillis();
		BurnFrequencyAccumulator accumulator =
			ensembleRunner.run(baseGrid, effectiveWind, seeds, MASTER_SEED);
		long ensembleMs = System.currentTimeMillis() - t0;
		log.info("Phase 1 [{}]: ensemble complete — {}ms ({} s), avg {} ms/run",
			runId, ensembleMs,
			String.format("%.1f", ensembleMs / 1000.0),
			String.format("%.0f", ensembleMs / (double) simulationConfig.getMonteCarloRuns()));

		// Stage 4: assemble result
		t0 = System.currentTimeMillis();
		PhaseOneResult result =
			riskMapAssembler.assemble(accumulator, ic, simulationConfig.getMonteCarloRuns());
		log.debug("Phase 1 [{}]: result assembly complete — {}ms", runId,
			System.currentTimeMillis() - t0);

		// Stage 5: analytics — derived entirely from already-computed data, no extra simulation
		RunAnalytics analytics = analyticsService.summarisePhaseOne(
			result.getDamagePotential(),
			result.getIgnitionLikelihood(),
			baseGrid,
			simulationConfig.getMonteCarloRuns());
		log.debug("Phase 1 [{}]: analytics — highRiskCells={}, highRiskAreaHa={}, " +
				"dominantVeg={}",
			runId,
			analytics.getHighRiskCellCount(),
			analytics.getHighRiskAreaHectares(),
			analytics.getDominantVegetationType());

		Instant completed = Instant.now();
		persistRunRecord(runId, SimulationModeEnum.PRE_FIRE, started, completed,
			buildParamsSnapshot(request), analytics);

		log.info("Phase 1 complete — runId={}, elapsed={}ms",
			runId, elapsed(started, completed));

		return resultAssemblerService.assemblePhaseOne(
			runId, baseGrid,
			result.getDamagePotential(),
			result.getIgnitionLikelihood(),
			analytics);
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

		RunAnalytics analytics = analyticsService.summarisePhaseTwo(
			steps, activeFireGrid, simulationConfig);
		log.debug("Phase 2 [{}]: analytics — burnedAreaHa={}, peakRosHa/h={}, " +
				"generations={}, perimeterCells={}",
			runId,
			analytics.getFinalBurnedAreaHectares(),
			analytics.getPeakRosHectaresPerHour(),
			analytics.getGenerationsRun(),
			analytics.getPerimeterCellCountFinal());

		persistRunRecord(runId, SimulationModeEnum.ACTIVE_FIRE, started, completed,
			buildParamsSnapshot(request), analytics);

		log.info("Phase 2 complete — runId={}, generations={}, elapsed={}ms",
			runId, steps.size(), elapsed(started, completed));

		return resultAssemblerService.assemblePhaseTwo(runId, activeFireGrid, steps, analytics);
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
	// Grid environment (GET /api/session/grid)
	// -------------------------------------------------------------------------

	/**
	 * Returns the static per-cell environmental data for the frontend map render.
	 * Built from CaGrid.environment and the elevation/slope arrays captured at
	 * grid init time. Throws IllegalStateException when grid is not ready —
	 * controller converts this to HTTP 503.
	 */
	public GridEnvironmentResponseDto getGridEnvironment() {
		assertGridReady();
		int cells = baseGrid.rows * baseGrid.cols;
		int[] vegOrdinals = new int[cells];

		for (int r = 0; r < baseGrid.rows; r++) {
			for (int c = 0; c < baseGrid.cols; c++) {
				int idx = r * baseGrid.cols + c;
				vegOrdinals[idx] = baseGrid.environment[r][c]
					.getVegetationType().ordinal();
			}
		}

		return new GridEnvironmentResponseDto(
			vegOrdinals,
			cachedElevationMetres,
			cachedSlopeDegrees,
			baseGrid.rows,
			baseGrid.cols,
			baseGrid.cellSizeMetres,
			minX, minY, maxX, maxY);
	}

	// -------------------------------------------------------------------------
	// Mode override (POST /api/session/mode)
	// -------------------------------------------------------------------------

	/**
	 * Manually sets the session mode. Does not alter any grid state or clear
	 * any simulation history. The 3-hour CV poll may subsequently override
	 * this back to ACTIVE_FIRE if a fire perimeter is detected — CV observation
	 * takes precedence over manual override by design.
	 */
	public synchronized SessionStatusResponseDto setMode(SimulationModeEnum newMode) {
		SimulationModeEnum previous = this.mode;
		this.mode = newMode;
		log.info("Session mode manually overridden: {} → {}", previous, newMode);
		return getSessionStatus();
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
								  Instant started, Instant completed, Map<String, Object> params,
								  RunAnalytics analytics) {
		runLogWriterService.write(
			new RunRecord(runId, phase, started, completed, params, null, analytics));
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