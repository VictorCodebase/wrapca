## WRaP CA Engine — Implementation Order \& Handoff Reference



This document is self-contained for use in a new chat. The proposal PDF will also be available.



---



### Project Identity

```

Language: Java 21 (Temurin 21.0.10)

Framework: Spring Boot 4.0.3

Build: Maven

Base package: com.victorkithinji.wrap.wrapca

Project name: WrapCa

```



---



### What this system is



WRaP is a two-phase wildfire CA engine exposed as a Spring Boot REST API. A Chromium frontend consumes the API — all computation is Java-side.



**Phase 1 (pre-fire):** Monte Carlo ensemble of CA runs to produce two output layers — ignition probability map (smoothed I(c) index) and damage potential map (burn frequency across N runs).



**Phase 2 (active fire):** Rothermel-embedded CA spread simulation. CV corrections injected at each satellite overpass to prevent error compounding.



The CA grid is a 2D array of 100m cells. Each cell holds a state (UNBURNED / BURNING / BURNED / NON\_COMBUSTIBLE) and an environment vector (NDVI, NDMI, slope, aspect, vegetation type). Only cells with at least one BURNING neighbour are evaluated per generation — this is the core efficiency constraint.

---

### Architecture layers (do not collapse these)

```

api/          → HTTP only, no logic

facade/       → startup orchestration, mode detection

grid/         → CA grid domain objects

ingestion/    → GeoTIFF reading, wind loading, data cache

rothermel/    → pure fire physics, no Spring dependencies

simulation/   → CA engine, Moore neighbourhood, active frontier

montecarlo/   → Phase 1 ensemble runner

correction/   → CV re-injection, suppressed zone tracking

output/       → result assembly, perimeter extraction

history/      → JSON run persistence

dto/          → API request/response shapes only

config/       → reads application.properties into typed beans

```

---

### application.properties (already in project)

```properties

spring.application.name=WrapCa

server.port=8080

wrap.data.root=./data

wrap.simulation.cell-size-metres=100

wrap.simulation.time-step-minutes=5

wrap.simulation.monte-carlo-runs=200

wrap.simulation.thread-pool-size=8

wrap.simulation.phase1-horizon-hours=24

wrap.cv.geotiff-path=./data/geotiff/latest\_cv\_output.tif

```

---

### pom.xml dependencies already confirmed working

- spring-boot-starter-web

- spring-boot-starter-validation

- spring-boot-starter-devtools

- spring-boot-starter-test

- lombok (with annotation processor path configured)

- gt-coverage, gt-geotiff, gt-referencing, gt-epsg-hsql (GeoTools 31.0, OSGeo repo)

- jackson-databind, jackson-datatype-jsr310

- commons-math3 3.6.1

- compiler plugin with `<release>21</release>`



---



### Implementation order



Work through these in sequence. Each group depends on the previous.



---



#### GROUP 1 — Domain foundation (no Spring, pure Java)

*These have zero dependencies on anything else in the project.*



**1. `grid/CellState.java`**

Enum: `UNBURNED, BURNING, BURNED, NON\_COMBUSTIBLE`



**2. `grid/CellEnvironment.java`**

Data class (Lombok `@Value` — immutable). Fields: `float ndvi, ndmi, slopeRadians, aspectRadians` and a `VegetationType` enum reference. This is the static per-cell environmental vector assigned at grid init and refreshed by CV correction.



**3. `grid/VegetationType.java`**

Enum referenced by CellEnvironment: `AFROMONTANE\_FOREST, MONTANE\_GRASSLAND, SHRUBLAND, BARE\_SOIL, WATER, BUILT`. This drives fuel parameter lookup in the Rothermel layer.



**4. `grid/CaGrid.java`**

Holds: `int\[]\[] states` (using CellState ordinals for speed), `CellEnvironment\[]\[] environment`, `int rows`, `int cols`, `double cellSizeMetres`. No Spring annotations. This object is the simulation's entire spatial state.



---



#### GROUP 2 — Fire physics (no Spring, pure Java, independently testable)

*Implement and unit test these against known Rothermel values before touching the engine.*



**5. `rothermel/FuelModelResolver.java`**

Maps `VegetationType` → fuel parameters (load, moisture of extinction, heat content, SAV ratio). Values come from `fuelmodels/east\_africa\_fuel\_models.json` in resources. Keep a static lookup — no database, no complexity.



**6. `rothermel/WindProjectionCalculator.java`**

Given a wind vector (speed + direction in degrees) and a Moore direction index (0–7), returns the effective wind component Ue along that direction. Negative projections clamped to zero.



**7. `rothermel/SlopeEffectCalculator.java`**

Given elevation of source cell and target cell plus distance, returns slope angle φs. Distance is `cellSize` for cardinal directions, `cellSize × √2` for diagonals.



**8. `rothermel/RothermelRosCalculator.java`**

Pure static methods. Takes fuel params, Ue, φs → returns ROS in metres per minute. This is the simplified Rothermel (1972) surface fire formula. No Spring annotations. This is the most important class to get right — validate against Andrews (2018) reference values.



---



#### GROUP 3 — Configuration (Spring, simple)



**9. `config/SimulationConfig.java`**

`@Configuration @ConfigurationProperties(prefix = "wrap.simulation")`. Lombok `@Data`. Fields mirror application.properties: `cellSizeMetres`, `timeStepMinutes`, `monteCarloRuns`, `threadPoolSize`, `phase1HorizonHours`.



**10. `config/CorsConfig.java`**

`@Configuration`. Permits localhost origins during development. One method, ~10 lines.



---



#### GROUP 4 — Ingestion (Spring services, external data boundary)



**11. `ingestion/IngestionCacheService.java`**

Checks `data/cache/` for a file matching today's date before triggering a re-fetch. Returns `Optional<Path>`. This prevents redundant processing on server restart.



**12. `ingestion/GeoTiffBandReaderService.java`**

Reads the CV GeoTIFF output using GeoTools. Extracts NDVI, NDMI, elevation bands into float\[]\[] arrays aligned to the CA grid. Returns a structured object the grid initialiser consumes.



**13. `ingestion/WindFieldLoaderService.java`**

Loads ERA5 wind data (initially from a local file, later from API). Interpolates to CA grid resolution. Returns a `WindField` object: two float\[]\[] arrays for speed and direction per cell.



**14. `ingestion/FirePerimeterParserService.java`**

Parses a CV-provided fire perimeter (GeoJSON polygon or cell coordinate list) into a `Set<Long>` of encoded cell indices (`row * gridWidth + col`). This is the initial BURNING cell set for Phase 2.



---



#### GROUP 5 — Grid initialisation (Spring service)



**15. `grid/GridInitialiserService.java`**

Consumes outputs of GeoTiffBandReaderService. Constructs CaGrid: assigns CellEnvironment per cell, marks NON\_COMBUSTIBLE cells (water, built areas from OSM layer). Entry point for the facade's startup sequence.



---



#### GROUP 6 — Simulation engine (Spring services)



**16. `simulation/ActiveCellFrontierTracker.java`**

Maintains a `HashSet<Long>` of cells that have at least one BURNING neighbour. Updated each generation — cells added when a neighbour ignites, removed when they themselves become BURNED or when all neighbours are BURNED. This is the efficiency constraint from the proposal: only frontier cells are evaluated.



**17. `simulation/IgnitionProbabilityResolver.java`**

For one target cell, iterates its BURNING neighbours, calls RothermelRosCalculator for each, computes Pₑ per neighbour, then resolves combined ignition probability: `1 - ∏(1 - Pₑⱼ)`. Returns a double.



**18. `simulation/MooreNeighbourEvaluator.java`**

For a given cell coordinate, returns the 8 Moore neighbours with their direction indices and distances. Handles grid boundary checks.



**19. `simulation/SimulationStepResult.java`**

Data class (Lombok `@Value`). Holds: `Set<Long> newlyBurnedCells`, `int generation`, `Instant timestamp`. One instance produced per generation step.



**20. `simulation/CaSpreadEngine.java`**

The core engine. Per generation: iterates frontier cells via ActiveCellFrontierTracker, calls IgnitionProbabilityResolver, resolves state transitions stochastically, updates grid, updates frontier, produces SimulationStepResult. Takes CaGrid + WindField as inputs. Used by both Phase 1 (Monte Carlo) and Phase 2 (active spread).



---



#### GROUP 7 — Monte Carlo ensemble (Spring services)



**21. `montecarlo/IgnitionLikelihoodIndexBuilder.java`**

Computes I(c) per cell: weighted combination of normalised NDMI, historical FIRMS fire density, OSM human activity proximity. Output is a float\[] probability weight array used for seeding. Runs once before ensemble.



**22. `montecarlo/IgnitionSeedSampler.java`**

Samples N ignition seed cells from the grid with probability proportional to I(c). Uses Commons Math for weighted sampling. Returns `List<Long>` of encoded cell indices.



**23. `montecarlo/BurnFrequencyAccumulator.java`**

Thread-safe accumulation of burn counts across N parallel runs. Uses `AtomicIntegerArray` sized `rows × cols`. Each completed run calls `increment(cellIndex)` for every cell it burned.



**24. `montecarlo/MonteCarloEnsembleRunner.java`**

Spawns N independent CaSpreadEngine instances via `ForkJoinPool`. Each run gets its own deep copy of CaGrid and a single ignition seed. On completion, aggregates into BurnFrequencyAccumulator. Key: each task is fully independent — no shared mutable state between runs.



**25. `montecarlo/RiskMapAssembler.java`**

Converts BurnFrequencyAccumulator counts → normalised damage potential values per cell. Combines with smoothed I(c) to produce the dual-layer Phase 1 output.



---



#### GROUP 8 — CV correction (Spring services)



**26. `correction/SuppressedZoneRegistry.java`**

Tracks cells temporarily set to NON\_COMBUSTIBLE based on suppression signatures detected in VIIRS thermal data (provided by CV module). Holds a `Map<Long, Instant>` of cell index → suppression expiry time. CaSpreadEngine checks this before evaluating any cell.



**27. `correction/CvStateInjectorService.java`**

Applies CV observation layer to a running CaGrid: forces confirmed BURNED cells, registers suppressed zones, refreshes NDMI for UNBURNED cells. Called at each satellite overpass interval.



---



#### GROUP 9 — Output assembly



**28. `output/SimulationResultAssembler.java`**

Converts CaGrid state + List<SimulationStepResult> → response DTOs. Produces compact JSON-friendly structures (flat arrays, not raw grid objects). Does not send GeoTIFF bytes over API.



**29. `output/PerimeterPolygonExtractor.java`**

Traces the boundary between BURNED/BURNING and UNBURNED cells → GeoJSON polygon with timestamp. Used for Phase 2 time-stamped perimeter overlays.



**30. `output/HeatmapRasterWriter.java`**

Writes Phase 1 risk maps to GeoTIFF for file export only. Not called during normal API responses.



---



#### GROUP 10 — History persistence (JSON files)



**31. `history/RunRecord.java`**

Lombok `@Value`. Fields: `String runId, SimulationPhase phase, Instant startedAt, Instant completedAt, Map<String,Object> parameters, String resultFilePath`.



**32. `history/RunLogWriterService.java`**

On simulation completion, serialises RunRecord to `data/runs/{timestamp}\_{phase}.json` using Jackson ObjectMapper. No database — one file per run.



**33. `history/RunLogReaderService.java`**

Lists `data/runs/` directory, deserialises RunRecord from each file. Returns `List<RunRecord>` sorted by date. Called by the facade on startup.



---



#### GROUP 11 — DTOs



**34. `dto/request/PhaseOneRunRequest.java`**

Fields: nothing required — Phase 1 uses the currently loaded grid. Optional override fields for wind speed/direction if the user wants to test a scenario.



**35. `dto/request/PhaseTwoRunRequest.java`**

Fields: `boolean cvDisabled`, `boolean manualIgnition`, `String manualIgnitionPolygonGeoJson` (nullable), `int simulationHours`.



**36. `dto/request/CvCorrectionRequest.java`**

Fields: `String observedPerimeterGeoJson`, `List<String> suppressedZoneCellIds`, `Map<String,Float> updatedMoistureValues`.



**37. `dto/response/SessionStatusResponse.java`**

Fields: `SimulationMode mode` (PRE\_FIRE / ACTIVE\_FIRE), grid summary (rows, cols, cellSize, bounds), `List<RunSummaryResponse> pastRuns`.



**38. `dto/response/PhaseOneResultResponse.java`**

Fields: `String runId`, `float\[] damagePotentialValues`, `float\[] ignitionProbabilityValues`, `int rows`, `int cols`.



**39. `dto/response/PhaseTwoResultResponse.java`**

Fields: `String runId`, `List<PerimeterSnapshot> perimetersByTimestamp` where each snapshot holds a GeoJSON polygon string and an ISO timestamp.



---



#### GROUP 12 — Facade and API (wire everything together last)



**40. `facade/WrapSessionFacade.java`**

`@Service`. Startup sequence: checks cache → loads GeoTIFF → initialises grid → checks for fire perimeter from CV → sets mode. Exposes methods called by controllers. Coordinates between all services.



**41. `api/GridController.java`**

`@RestController`. Single endpoint: `GET /api/session/status` → calls facade → returns SessionStatusResponse.



**42. `api/SimulationController.java`**

`@RestController`. Endpoints: `POST /api/simulation/phase-one/run`, `POST /api/simulation/phase-two/run`, `POST /api/simulation/phase-two/correct`.



**43. `api/RunHistoryController.java`**

`@RestController`. Endpoints: `GET /api/runs`, `GET /api/runs/{runId}`.



---



### Key implementation constraints to preserve



- `RothermelRosCalculator` — static methods only, zero Spring annotations, unit test in isolation first

- Cell coordinates encoded as `long = row * gridWidth + col` everywhere — never use `int\[]` as map keys

- Monte Carlo runs — each task owns a full deep copy of CaGrid, `AtomicIntegerArray` for accumulation

- CV corrections are hard state overrides, not probabilistic adjustments

- API responses never contain raw grid arrays or GeoTIFF bytes — always compact numeric structures

- `SuppressedZoneRegistry` data source: VIIRS thermal suppression signatures from CV module

- Run history: flat JSON files in `data/runs/`, no database