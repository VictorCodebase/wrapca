package com.victorkithinji.wrap.wrapca.simulation;

import com.victorkithinji.wrap.wrapca.config.SimulationConfig;
import com.victorkithinji.wrap.wrapca.correction.SuppressedZoneRegistry;
import com.victorkithinji.wrap.wrapca.grid.CaGrid;
import com.victorkithinji.wrap.wrapca.grid.CellStateEnum;
import com.victorkithinji.wrap.wrapca.grid.VegetationTypeEnum;
import com.victorkithinji.wrap.wrapca.ingestion.WindField;
import com.victorkithinji.wrap.wrapca.rothermel.FuelModel;
import com.victorkithinji.wrap.wrapca.rothermel.FuelModelResolver;
import com.victorkithinji.wrap.wrapca.rothermel.RothermelRosCalculator;
import com.victorkithinji.wrap.wrapca.rothermel.WindProjectionCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Random;

import static com.victorkithinji.wrap.wrapca.simulation.GridTestFactory.*;
import static org.assertj.core.api.Assertions.assertThat;




/**
 * Integration tests for the simulation layer (Groups 1–6).
 *
 * These tests exercise the full chain:
 *   CaGrid → ActiveCellFrontierTracker → IgnitionProbabilityResolver
 *   → WindProjectionCalculator / SlopeEffectCalculator / RothermelRosCalculator
 *   → CaSpreadEngine
 *
 * No Spring context — all collaborators are wired manually.
 *
 * RNG strategy:
 *   ALWAYS_IGNITE (nextDouble = 0.0) — any Pe > 0 triggers ignition.
 *   Used to assert that fire CAN spread under a given physical scenario.
 *
 *   NEVER_IGNITE (nextDouble = 1.0) — nothing ever ignites regardless of Pe.
 *   Used to isolate state-machine behaviour from the physics.
 *
 *   Seeded Random — deterministic multi-step runs for spread asymmetry tests.
 *
 * SuppressedZoneRegistry:
 *   Uses SuppressedZoneRegistryStub (test-tree only). Always returns false
 *   from isActive(). Replace with the real bean once Group 8 is implemented.
 *   See SuppressedZoneRegistryStub for the open TODO.
 */
public class FireSpreadIntegrationTest {
    private static final float DRY_NDMI = 0.05f; // Below grassland extinction 0.25
    private static final float WET_NDMI = 0.85f; // very wet

    private static final Random ALWAYS_IGNITE = new Random(){
        @Override public double nextDouble() {return 0.0; } // I udefine as random because client method calling it indiscreminately expects  type Random
    };

    private static final Random NEVER_IGNITE = new Random(){
        @Override public double nextDouble() {return 1.0;}
    };

    private CaSpreadEngine engine;
    private SuppressedZoneRegistry noSuppression;

    @BeforeEach
    void setUp(){
        SimulationConfig config = new SimulationConfig();
        config.setTimeStepMinutes(5);
        config.setCellSizeMetres(CELL_SIZE); // why is this problematic while the config getter above it is working fine

        engine = new CaSpreadEngine(new IgnitionProbabilityResolver(), config);
        noSuppression = new SuppressedZoneRegistry();
    }

    @Test
    void drySavannah_fireStartsAtOneEnd_spreadsThroughEntireGrid(){
        CaGrid grid = unburnedGridVegetation(1, 10, DRY_NDMI, VegetationTypeEnum.GRASSLAND);
        ignite(grid, 0,0);
        WindField wind = calmWind(1, 10);

        engine.run(grid, wind, noSuppression, 15, ALWAYS_IGNITE);

        assertThat(isFullyBurned(grid)).isTrue();
    }

    @Test
    void drySavannah_fireSpreadBeyondImmediateNeighbours(){
        // 5 by 5 fire at the center, after enough steps, cells far from origin are burned
        CaGrid grid = unburnedGridVegetation(5,5,DRY_NDMI, VegetationTypeEnum.GRASSLAND);
        ignite(grid, 2,2);
        WindField wind = calmWind(5,5);

        engine.run(grid, wind, noSuppression, 20, ALWAYS_IGNITE);

        // conrner cells two steps away are expected to be burned by 10 generations
        assertThat(grid.getState(0,0)).isEqualTo(CellStateEnum.BURNED);
        assertThat(grid.getState(0,4)).isEqualTo(CellStateEnum.BURNED);
        assertThat(grid.getState(4,4)).isEqualTo(CellStateEnum.BURNED);
        assertThat(grid.getState(4,0)).isEqualTo(CellStateEnum.BURNED);
    }

    // Wind speed and direction influence fire spread
    @Test
    void strongerWind_reachesTargetRowFaster() {
        int rows = 50, cols = 50;
        int targetRow = 40; // how fast does fire reach row 40 from row 5

        int stepsWithWeakWind  = stepsToReachRow(rows, cols, 5, targetRow, 1.0f,  0.0f);
        int stepsWithStrongWind = stepsToReachRow(rows, cols, 5, targetRow, 15.0f, 0.0f);

        System.out.printf("Weak wind steps: %d  |  Strong wind steps: %d%n",
                stepsWithWeakWind, stepsWithStrongWind);

        assertThat(stepsWithStrongWind).isLessThan(stepsWithWeakWind);
    }

    private int stepsToReachRow(int rows, int cols, int igniteRow, int targetRow,
                                float speed, float dir) {
        CaGrid grid = unburnedGrid(rows, cols, DRY_NDMI);
        for (int c = 0; c < cols; c++) ignite(grid, igniteRow, c);
        WindField wind = uniformWind(rows, cols, speed, dir);

        List<SimulationStepResult> results =
                engine.run(grid, wind, noSuppression, 200, new Random(42L));

        for (SimulationStepResult step : results) {
            for (long idx : step.getNewlyIgnitedCells()) {
                int r = (int)(idx / cols);
                if (r >= targetRow) return step.getGeneration();
            }
        }
        return Integer.MAX_VALUE; // never reached
    }

    @Test
    void diagnostic_effectiveWindComponent_idDirectional(){
        // Wind from north (0 deg) blows southward
        // directional index 4 = south (from source to target)
        // southward component must be positive and larger than the northward component
        double southward = WindProjectionCalculator.effectiveComponent(8.0, 0.0, 4); // S
        double northward = WindProjectionCalculator.effectiveComponent(8.0, 0.0, 0); //N

        System.out.print("SOUTHWARD: ");
        System.out.println(southward);
        System.out.print("NORTHWARD: ");
        System.out.println(northward);

        assertThat(southward).isGreaterThan(0.0);
        assertThat(northward).isEqualTo(0.0);
    }

    @Test
    void diagnostic_rosAndPe_acrossWindSpeeds() {
        FuelModel grass = FuelModelResolver.resolve(VegetationTypeEnum.AFROMONTANE_FOREST);
        double moisture = 0.05;
        double timeStep = 5.0;  // test new config
        double distance = 10.0; // test cell size

        System.out.println("\nWind(m/s) | ROS(m/min) | Pe");
        for (double wind : new double[]{0.0, 0.5, 1.0, 2.0, 3.0, 5.0, 8.0, 15.0}) {
            double ros = RothermelRosCalculator.computeRos(grass, moisture, wind, 0.0);
            double pe = Math.min(1.0, (ros * timeStep) / distance);
            System.out.printf("%-10.1f - %-12.4f - %.6f%n", wind, ros, pe);
        }
    }

    @Test
    void eastwardWind_moreSpreadTowardEastThanWest () {
        int rows = 30, cols = 11;
        CaGrid grid = unburnedGridVegetation(rows, cols, DRY_NDMI, VegetationTypeEnum.SHRUBLAND);
        // I tried to use shrubland instead of grassland

        // for (int r = 0; r < rows; r++) ignite(grid, r, 0);
        ignite(grid,0,0);
        WindField wind = uniformWind(rows, cols, 8.0f, 270.0f); // FROM west

        engine.run(grid, wind, noSuppression, 20, ALWAYS_IGNITE);

        int burnedEast = countBurnedInColumns(grid, 6, 10);
        int burnedWest = countBurnedInColumns(grid, 0, 4);

        assertThat(burnedEast).isGreaterThan(burnedWest);
    }

    @Test
    void strongerWindProducesMoreSpreadThanWeakerWind(){
        int rows = 9, cols = 9;

        CaGrid calmGrid = unburnedGrid(rows, cols, DRY_NDMI);
        CaGrid windyGrid = unburnedGrid(rows, cols, DRY_NDMI);

        ignite(calmGrid, 4,4);
        ignite(windyGrid, 4,4);

        WindField calm = calmWind(rows, cols);
        WindField windy = uniformWind(rows, cols, 10.0f, 0.0f);

        //implement seeded RNG (Randmin Number Generator) to get the same value, sort of, so that only physics differs
        Random seededForCalm = new Random(42L);
        Random seededForWindy = new Random(42L);

        engine.run(calmGrid, calm, noSuppression, 5, seededForCalm);
        engine.run(windyGrid, windy, noSuppression, 5, seededForWindy);

        int burnedCalm = countCellsInState(calmGrid, CellStateEnum.BURNED) +
                countCellsInState(calmGrid, CellStateEnum.BURNING);
        int burnedWindy = countCellsInState(windyGrid, CellStateEnum.BURNED) +
                countCellsInState(windyGrid, CellStateEnum.BURNING);

        assertThat(burnedWindy).isGreaterThan(burnedCalm);

    }

    // Wet fuel stops spread
    @Test
    void WetSavannah_fireDoesNotSpread(){
        CaGrid grid = unburnedGrid(7,7,WET_NDMI);
        ignite(grid, 3,3);
        WindField wind = uniformWind(7,7,8.0f, 0.0f);
        // strong winds to encourage spread

        engine.run(grid, wind, noSuppression, 10, ALWAYS_IGNITE);

        // Only the seeded cell should burn, the rest should retain unburned state
        int unburned = countCellsInState(grid, CellStateEnum.UNBURNED);
        assertThat(unburned).isEqualTo(48);// 7 X 7 -1

    }

    // Vegetation type influences spread - Forests burn slower than grasslands
    @Test
    void forest_spreadsSlowerThanGrassland_underSameConditions(){
        // same RNG seed, wind, and dry state
        // grassland expected to burn more due to lower moisture of extinction
        int rows = 9, cols = 9;
        CaGrid grassLands = unburnedGridVegetation(rows, cols, DRY_NDMI, VegetationTypeEnum.GRASSLAND);
        CaGrid forest = unburnedGridVegetation(rows, cols, DRY_NDMI, VegetationTypeEnum.AFROMONTANE_FOREST);

        ignite(grassLands, 4, 4);
        ignite(forest, 4,4);

        WindField wind = uniformWind(rows, rows, 5.0f, 0.0f);

        engine.run(grassLands, wind, noSuppression, 4, new Random(7L));
        engine.run(forest, wind, noSuppression, 4, new Random(7L));

        int burnedGrass = countCellsInState(grassLands, CellStateEnum.BURNED)
                + countCellsInState(grassLands, CellStateEnum.BURNING);
        int burnedForest = countCellsInState(forest, CellStateEnum.BURNED)
                + countCellsInState(forest, CellStateEnum.BURNING);

        assertThat(burnedGrass).isGreaterThan(burnedForest);
    }

    // Steps results - timestamps and generated metadata
    @Test
    void stepResults_areProduced_withCorrectGenerationIndices(){
        CaGrid grid = unburnedGrid(7, 7, DRY_NDMI);
        ignite(grid, 3,3);
        WindField wind = uniformWind(7,7,5.0f, 180f);

        List<SimulationStepResult> results =
                engine.run(grid, wind, noSuppression, 5, ALWAYS_IGNITE);

        assertThat(results).isNotEmpty();

        for (int i=0; i<results.size(); i++){
            assertThat(results.get(i).getGeneration()).isEqualTo(i);
        }
    }

    @Test
    void stepResults_timestampsAreNonDecreasing(){
        CaGrid grid = unburnedGrid(7,7, DRY_NDMI);
        ignite(grid, 3,3);
        WindField wind = uniformWind(7,7,5.0f,180.0f);

        List<SimulationStepResult> results =
                engine.run(grid,wind,noSuppression, 5, ALWAYS_IGNITE);

        for (int i = 1; i<results.size(); i++){
            Instant prev = results.get(i-1).getTimestamp();
            Instant curr = results.get(i).getTimestamp();
            assertThat(curr).isAfter(prev);
        }
    }

    @Test
    void stepResults_newlyIgnitedCells_areRecordedPerGeneration(){
        // on a 3x3 grid ALWAYS IGNITE, gen 0 should record at least one
        // newly ignited cell (the frontier neighbours of the seed cell)

        CaGrid grid = unburnedGrid(3,3,DRY_NDMI);
        ignite(grid,1,1);
        WindField wind = calmWind(3,3);

        List<SimulationStepResult> results =
                engine.run(grid, wind, noSuppression, 1, ALWAYS_IGNITE);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getNewlyIgnitedCells()).isNotEmpty();
    }

    @Test
    void stepResults_earlyTermination_whenFrontierExhausted(){
        // since a 3x3 is expected to be consumed quickly (under conditions, 20 gens should terminate before end
        CaGrid grid = unburnedGrid(3,3,DRY_NDMI);
        ignite(grid,1,1);
        WindField wind = uniformWind(3,3,5.0f, 0.0f);

        List<SimulationStepResult> results =
                engine.run(grid, wind, noSuppression, 20, ALWAYS_IGNITE);

        assertThat(results.size()).isLessThan(20);
        assertThat(countCellsInState(grid, CellStateEnum.UNBURNED)).isEqualTo(0);
    }

    // Ensure state machine integrity
    @Test
    void nonCombustibleCell_neverIgnites(){
        CaGrid grid = unburnedGrid(3,3, DRY_NDMI);
        ignite(grid, 1,0);
        grid.setState(1,1,CellStateEnum.NON_COMBUSTIBLE);
        WindField wind = calmWind(3,3);

        engine.run(grid, wind, noSuppression, 5, ALWAYS_IGNITE);

        assertThat(grid.getState(1,1)).isEqualTo(CellStateEnum.NON_COMBUSTIBLE);
    }

    @Test
    void burningCell_transitionsToBurned_afterOneStep(){
        CaGrid grid = unburnedGrid(3,3 , DRY_NDMI);
        ignite(grid,1,1);
        WindField wind = calmWind(3, 3);

        engine.run(grid, wind, noSuppression, 1, NEVER_IGNITE);

        assertThat(grid.getState(1, 1)).isEqualTo(CellStateEnum.BURNED);
    }

    @Test
    void fireCannotBurn_throughBurnedCells(){
        // I expect that if a line of burned cells run across a grid
        // then fire shall not burn across the burned zone
        int cols = 5, rows = 11;
        CaGrid grid = unburnedGrid(rows, cols, DRY_NDMI);

        for (int c = 0; c < cols; c++) grid.setState(5,c, CellStateEnum.BURNED);

        ignite(grid,0, 0);
        WindField wind = uniformWind(11, 5, 10, 0.0f);

        engine.run(grid, wind, noSuppression, 20, ALWAYS_IGNITE);

        // I expect that all cells below row 5 to not be burned
        int burnedSouth = countBurnedInRows(grid, 6, 10);
        assertThat(burnedSouth).isEqualTo(0);
    }
}
