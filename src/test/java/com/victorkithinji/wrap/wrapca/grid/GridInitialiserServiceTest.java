package com.victorkithinji.wrap.wrapca.grid;

import com.victorkithinji.wrap.wrapca.ingestion.EsaBandLayout;
import com.victorkithinji.wrap.wrapca.ingestion.GridBands;
import com.victorkithinji.wrap.wrapca.ingestion.RoadLayer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for GridInitialiserService.
 *
 * No Spring context, no mocks. GridBands, ESA codes, and RoadLayer are
 * constructed inline. The service has no Spring dependencies once built directly.
 */
class GridInitialiserServiceTest {

    private GridInitialiserService service;

    @BeforeEach
    void setUp() {
        service = new GridInitialiserService();
    }

    // =========================================================================
    // Grid structure
    // =========================================================================

    @Test
    void gridDimensionsMatchBands() {
        GridInitResult result = service.build(
                uniformBands(4, 6, 100.0),
                uniformEsaCodes(4, 6, EsaBandLayout.CODE_GRASSLAND),
                emptyRoads());

        assertThat(result.getGrid().rows).isEqualTo(4);
        assertThat(result.getGrid().cols).isEqualTo(6);
        assertThat(result.getGrid().cellSizeMetres).isEqualTo(100.0);
    }

    // =========================================================================
    // Cell state assignment — ESA codes drive combustibility
    // =========================================================================

    @Test
    void combustibleEsaCode_cellIsUnburned() {
        GridInitResult result = service.build(
                uniformBands(2, 2, 100.0),
                uniformEsaCodes(2, 2, EsaBandLayout.CODE_TREE_COVER),
                emptyRoads());

        assertThat(result.getGrid().states[0][0])
                .isEqualTo(CellStateEnum.UNBURNED.ordinal());
    }

    @Test
    void esaBuiltUp_cellIsNonCombustible() {
        GridInitResult result = service.build(
                uniformBands(2, 2, 100.0),
                uniformEsaCodes(2, 2, EsaBandLayout.CODE_BUILT_UP),
                emptyRoads());

        assertThat(result.getGrid().states[0][0])
                .isEqualTo(CellStateEnum.NON_COMBUSTIBLE.ordinal());
    }

    @Test
    void esaWater_cellIsNonCombustible() {
        GridInitResult result = service.build(
                uniformBands(2, 2, 100.0),
                uniformEsaCodes(2, 2, EsaBandLayout.CODE_PERMANENT_WATER),
                emptyRoads());

        assertThat(result.getGrid().states[0][0])
                .isEqualTo(CellStateEnum.NON_COMBUSTIBLE.ordinal());
    }

    @Test
    void esaSnowIce_cellIsNonCombustible() {
        GridInitResult result = service.build(
                uniformBands(2, 2, 100.0),
                uniformEsaCodes(2, 2, EsaBandLayout.CODE_SNOW_ICE),
                emptyRoads());

        assertThat(result.getGrid().states[0][0])
                .isEqualTo(CellStateEnum.NON_COMBUSTIBLE.ordinal());
    }

    @Test
    void esaMangroves_cellIsNonCombustible() {
        GridInitResult result = service.build(
                uniformBands(2, 2, 100.0),
                uniformEsaCodes(2, 2, EsaBandLayout.CODE_MANGROVES),
                emptyRoads());

        assertThat(result.getGrid().states[0][0])
                .isEqualTo(CellStateEnum.NON_COMBUSTIBLE.ordinal());
    }

    @Test
    void esaBareSparse_cellIsUnburned() {
        // BARE_SOIL maps to a combustible type — only special ESA codes force NON_COMBUSTIBLE
        GridInitResult result = service.build(
                uniformBands(2, 2, 100.0),
                uniformEsaCodes(2, 2, EsaBandLayout.CODE_BARE_SPARSE),
                emptyRoads());

        assertThat(result.getGrid().states[0][0])
                .isEqualTo(CellStateEnum.UNBURNED.ordinal());
    }

    // =========================================================================
    // Vegetation type resolution — from ESA codes, not NDVI
    // =========================================================================

    @Test
    void vegetationTypeFromEsaCode_notNdvi() {
        // NDVI 0.9 would imply FOREST under old thresholding logic.
        // ESA says CROPLAND — that must win.
        float[][] ndvi = filled(1, 1, 0.9f);
        GridBands bands = bandsWithNdvi(1, 1, 100.0, ndvi);
        int[][] esaCodes = {{ EsaBandLayout.CODE_CROPLAND }};

        GridInitResult result = service.build(bands, esaCodes, emptyRoads());

        assertThat(result.getGrid().environment[0][0].getVegetationType())
                .isEqualTo(VegetationTypeEnum.CROPLAND);
    }

    @Test
    void allCombustibleEsaCodesResolveCorrectly() {
        assertVegType(EsaBandLayout.CODE_TREE_COVER,        VegetationTypeEnum.AFROMONTANE_FOREST);
        assertVegType(EsaBandLayout.CODE_SHRUBLAND,         VegetationTypeEnum.SHRUBLAND);
        assertVegType(EsaBandLayout.CODE_GRASSLAND,         VegetationTypeEnum.GRASSLAND);
        assertVegType(EsaBandLayout.CODE_CROPLAND,          VegetationTypeEnum.CROPLAND);
        assertVegType(EsaBandLayout.CODE_BARE_SPARSE,       VegetationTypeEnum.BARE_SOIL);
        assertVegType(EsaBandLayout.CODE_PERMANENT_WATER,   VegetationTypeEnum.WATER);
        assertVegType(EsaBandLayout.CODE_BUILT_UP,          VegetationTypeEnum.BUILT);
    }

    // =========================================================================
    // CellEnvironment band population
    // =========================================================================

    @Test
    void ndviAndNdmiPopulatedFromBands() {
        GridBands bands = new GridBands(
                new float[][]{{ 0.72f }}, new float[][]{{ 0.31f }},
                filled(1, 1, 1000f), filled(1, 1, 10f), filled(1, 1, 1.0f),
                1, 1, 100.0, 0, 0, 100, 100);
        GridInitResult result = service.build(bands, new int[][]{{ EsaBandLayout.CODE_GRASSLAND }}, emptyRoads());

        // NDVI is stored as-is — no scaling applied
        assertThat(result.getGrid().environment[0][0].getNdvi()).isEqualTo(0.72f, within(1e-6f));
        // NDMI is scaled: 0.03 + (0.31 − (−0.1)) * 0.35 = 0.1735
        assertThat(result.getGrid().environment[0][0].getNdmi()).isEqualTo(0.1735f, within(1e-4f));
    }

    @Test
    void slopeIsConvertedFromDegreesToRadians() {
        GridBands bands = bandsWithSlope(1, 1, 100.0, 45.0f);
        GridInitResult result = service.build(bands, new int[][]{{ EsaBandLayout.CODE_GRASSLAND }}, emptyRoads());

        float expected = (float) Math.toRadians(45.0);
        assertThat(result.getGrid().environment[0][0].getSlopeRadians())
                .isEqualTo(expected, within(1e-5f));
    }

    @Test
    void elevationIsForwardedFromBands() {
        GridBands bands = bandsWithElevation(1, 1, 100.0, 2450.0f);
        GridInitResult result = service.build(bands, new int[][]{{ EsaBandLayout.CODE_GRASSLAND }}, emptyRoads());

        assertThat(result.getGrid().environment[0][0].getElevationMetres())
                .isEqualTo(2450.0f, within(0.1f));
    }

    @Test
    void aspectPassedThroughUnchanged() {
        float aspectRad = 1.5707963f;
        GridBands bands = bandsWithAspect(1, 1, 100.0, aspectRad);
        GridInitResult result = service.build(bands, new int[][]{{ EsaBandLayout.CODE_GRASSLAND }}, emptyRoads());

        assertThat(result.getGrid().environment[0][0].getAspectRadians())
                .isEqualTo(aspectRad, within(1e-6f));
    }

    // =========================================================================
    // NDMI scaling
    // =========================================================================

    @Test
    void rawNdmiIsScaledNotStoredDirectly() {
        // Raw NDMI 0.3 stored directly would be 0.3 — above grassland extinction (0.15).
        // Scaled it should be well below 0.3 and within the physical moisture range.
        GridBands bands = bandsWithNdmi(1, 1, 100.0, 0.3f);
        GridInitResult result = service.build(bands,
                new int[][]{{ EsaBandLayout.CODE_GRASSLAND }}, emptyRoads());

        float storedNdmi = result.getGrid().environment[0][0].getNdmi();
        assertThat(storedNdmi).isNotEqualTo(0.3f);
        assertThat(storedNdmi).isBetween(0.03f, 0.40f);
    }

    @Test
    void criticallyDryNdmiMapsToMinimumMoisture() {
        // NDMI −0.1 is the dry anchor → should map to 0.03 (minimum)
        GridBands bands = bandsWithNdmi(1, 1, 100.0, -0.1f);
        GridInitResult result = service.build(bands,
                new int[][]{{ EsaBandLayout.CODE_GRASSLAND }}, emptyRoads());

        assertThat(result.getGrid().environment[0][0].getNdmi())
                .isEqualTo(0.03f, within(1e-4f));
    }

    @Test
    void saturatedNdmiIsClamped() {
        // NDMI 1.0 should clamp to MOISTURE_MAX (0.40)
        GridBands bands = bandsWithNdmi(1, 1, 100.0, 1.0f);
        GridInitResult result = service.build(bands,
                new int[][]{{ EsaBandLayout.CODE_GRASSLAND }}, emptyRoads());

        assertThat(result.getGrid().environment[0][0].getNdmi())
                .isEqualTo(0.40f, within(1e-4f));
    }

    @Test
    void scaledMoistureIsMonotonicallyIncreasing() {
        // Higher raw NDMI must always produce higher or equal scaled moisture
        float[] rawValues = { -1.0f, -0.5f, -0.1f, 0.0f, 0.1f, 0.3f, 0.5f, 1.0f };
        float prev = -1f;
        for (float raw : rawValues) {
            GridBands bands = bandsWithNdmi(1, 1, 100.0, raw);
            float scaled = service.build(bands,
                            new int[][]{{ EsaBandLayout.CODE_GRASSLAND }}, emptyRoads())
                    .getGrid().environment[0][0].getNdmi();
            assertThat(scaled).as("NDMI %.2f should be >= previous %.2f", raw, prev)
                    .isGreaterThanOrEqualTo(prev);
            prev = scaled;
        }
    }

    // =========================================================================
    // Road proximity
    // =========================================================================

    @Test
    void emptyRoadLayer_allCellsGetMaxValue() {
        GridInitResult result = service.build(
                uniformBands(2, 2, 100.0),
                uniformEsaCodes(2, 2, EsaBandLayout.CODE_GRASSLAND),
                emptyRoads());

        float[][] prox = result.getRoadProximityMetres();
        for (int r = 0; r < 2; r++)
            for (int c = 0; c < 2; c++)
                assertThat(prox[r][c]).isEqualTo(Float.MAX_VALUE);
    }

//    @Test
//    void roadAtCellCentre_proximityIsZero() {
//        // 1x1 grid, 100m cell, origin at (0,0).
//        // Cell [0][0] centre is at (50, -50) in UTM (minX=0, maxY=0).
//        // Place a road vertex exactly there.
//        // 1x1 grid, 100m cell, minX=0, maxY=0 → cell [0][0] centre is (50.0, -50.0)
//        RoadLayer roads = new RoadLayer(List.of(new double[][]{{ 50.0, -50.0 }}));
//
//        GridBands bands = new GridBands(
//                filled(1, 1, 0.5f), filled(1, 1, 0.2f), filled(1, 1, 1000f),
//                filled(1, 1, 5f), filled(1, 1, 0.8f),
//                1, 1, 100.0, 0.0, -100.0, 100.0, 0.0);  // minX=0, minY=-100, maxX=100, maxY=0
//
//        GridInitResult result = service.build(bands, new int[][]{{ EsaBandLayout.CODE_GRASSLAND }}, roads);
//
//        assertThat(result.getRoadProximityMetres()[0][0]).isEqualTo(0.0f, within(1e-3f));
//    }

    @Test
    void roadProximityDimensionsMatchGrid() {
        GridInitResult result = service.build(
                uniformBands(5, 7, 100.0),
                uniformEsaCodes(5, 7, EsaBandLayout.CODE_GRASSLAND),
                emptyRoads());

        assertThat(result.getRoadProximityMetres().length).isEqualTo(5);
        assertThat(result.getRoadProximityMetres()[0].length).isEqualTo(7);
    }

    // =========================================================================
    // Independence between calls
    // =========================================================================

    @Test
    void repeatedBuildsReturnIndependentGrids() {
        GridBands bands = uniformBands(3, 3, 100.0);
        int[][] esa     = uniformEsaCodes(3, 3, EsaBandLayout.CODE_GRASSLAND);

        CaGrid first  = service.build(bands, esa, emptyRoads()).getGrid();
        CaGrid second = service.build(bands, esa, emptyRoads()).getGrid();

        first.states[1][1] = CellStateEnum.BURNING.ordinal();
        assertThat(second.states[1][1]).isEqualTo(CellStateEnum.UNBURNED.ordinal());
    }

    // =========================================================================
    // Builders
    // =========================================================================

    private void assertVegType(int esaCode, VegetationTypeEnum expected) {
        GridInitResult result = service.build(
                uniformBands(1, 1, 100.0),
                new int[][]{{ esaCode }},
                emptyRoads());
        assertThat(result.getGrid().environment[0][0].getVegetationType())
                .as("ESA code %d", esaCode)
                .isEqualTo(expected);
    }

    private GridBands uniformBands(int rows, int cols, double cellSize) {
        return new GridBands(
                filled(rows, cols, 0.5f), filled(rows, cols, 0.2f), filled(rows, cols, 1000f),
                filled(rows, cols, 5.0f), filled(rows, cols, 0.8f),
                rows, cols, cellSize,
                0, -(rows * cellSize), cols * cellSize, 0);
    }

    private GridBands bandsWithNdvi(int rows, int cols, double cellSize, float[][] ndvi) {
        return new GridBands(ndvi,
                filled(rows, cols, 0.2f), filled(rows, cols, 1000f),
                filled(rows, cols, 5.0f), filled(rows, cols, 0.8f),
                rows, cols, cellSize,
                0, -(rows * cellSize), cols * cellSize, 0);
    }

    private GridBands bandsWithNdmi(int rows, int cols, double cellSize, float rawNdmi) {
        return new GridBands(
                filled(rows, cols, 0.5f),
                filled(rows, cols, rawNdmi),
                filled(rows, cols, 1000f),
                filled(rows, cols, 5.0f), filled(rows, cols, 0.8f),
                rows, cols, cellSize,
                0, -(rows * cellSize), cols * cellSize, 0);
    }

    private GridBands bandsWithSlope(int rows, int cols, double cellSize, float slopeDeg) {
        return new GridBands(
                filled(rows, cols, 0.5f), filled(rows, cols, 0.2f), filled(rows, cols, 1000f),
                filled(rows, cols, slopeDeg), filled(rows, cols, 0.8f),
                rows, cols, cellSize,
                0, -(rows * cellSize), cols * cellSize, 0);
    }

    private GridBands bandsWithElevation(int rows, int cols, double cellSize, float elevM) {
        return new GridBands(
                filled(rows, cols, 0.5f), filled(rows, cols, 0.2f),
                filled(rows, cols, elevM),
                filled(rows, cols, 5.0f), filled(rows, cols, 0.8f),
                rows, cols, cellSize,
                0, -(rows * cellSize), cols * cellSize, 0);
    }

    private GridBands bandsWithAspect(int rows, int cols, double cellSize, float aspectRad) {
        return new GridBands(
                filled(rows, cols, 0.5f), filled(rows, cols, 0.2f), filled(rows, cols, 1000f),
                filled(rows, cols, 5.0f), filled(rows, cols, aspectRad),
                rows, cols, cellSize,
                0, -(rows * cellSize), cols * cellSize, 0);
    }

    private int[][] uniformEsaCodes(int rows, int cols, int code) {
        int[][] codes = new int[rows][cols];
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++)
                codes[r][c] = code;
        return codes;
    }

    private RoadLayer emptyRoads() {
        return new RoadLayer(List.of());
    }

    private float[][] filled(int rows, int cols, float value) {
        float[][] arr = new float[rows][cols];
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++)
                arr[r][c] = value;
        return arr;
    }

    private static org.assertj.core.data.Offset<Float> within(float d) {
        return org.assertj.core.data.Offset.offset(d);
    }
}