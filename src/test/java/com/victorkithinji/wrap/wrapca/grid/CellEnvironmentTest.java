package com.victorkithinji.wrap.wrapca.grid;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CellEnvironment}.
 *
 * CellEnvironment is an immutable value object (@Value). These tests verify
 * construction, field access, and Lombok-generated equality — important because
 * equality is used in assertions throughout the simulation tests.
 */
class CellEnvironmentTest {

    // Shared fixture representing a dense Afromontane forest cell
    private static final CellEnvironment FOREST_CELL = new CellEnvironment(
            0.72f,          // ndvi  — dense canopy
            0.45f,          // ndmi  — moderately moist
            0.1745f,        // slopeRadians — ~10°
            1.5708f,        // aspectRadians — east-facing (~90°)
            VegetationTypeEnum.AFROMONTANE_FOREST
    );

    @Test
    void constructor_storesAllFields() {
        assertEquals(0.72f,   FOREST_CELL.getNdvi(),          1e-5f);
        assertEquals(0.45f,   FOREST_CELL.getNdmi(),          1e-5f);
        assertEquals(0.1745f, FOREST_CELL.getSlopeRadians(),  1e-4f);
        assertEquals(1.5708f, FOREST_CELL.getAspectRadians(), 1e-4f);
        assertEquals(VegetationTypeEnum.AFROMONTANE_FOREST, FOREST_CELL.getVegetationType());
    }

    @Test
    void equalInstances_areEqual() {
        CellEnvironment duplicate = new CellEnvironment(
                0.72f, 0.45f, 0.1745f, 1.5708f, VegetationTypeEnum.AFROMONTANE_FOREST
        );
        assertEquals(FOREST_CELL, duplicate,
                "Two CellEnvironment instances with identical fields must be equal");
    }

    @Test
    void differentNdvi_notEqual() {
        CellEnvironment drier = new CellEnvironment(
                0.30f, 0.45f, 0.1745f, 1.5708f, VegetationTypeEnum.AFROMONTANE_FOREST
        );
        assertNotEquals(FOREST_CELL, drier);
    }

    @Test
    void differentVegetationType_notEqual() {
        CellEnvironment grassland = new CellEnvironment(
                0.72f, 0.45f, 0.1745f, 1.5708f, VegetationTypeEnum.MONTANE_GRASSLAND
        );
        assertNotEquals(FOREST_CELL, grassland);
    }

    @Test
    void equalInstances_haveSameHashCode() {
        CellEnvironment duplicate = new CellEnvironment(
                0.72f, 0.45f, 0.1745f, 1.5708f, VegetationTypeEnum.AFROMONTANE_FOREST
        );
        assertEquals(FOREST_CELL.hashCode(), duplicate.hashCode());
    }

    @Test
    void ndvi_acceptsNegativeValues() {
        // Water and cloud pixels can produce negative NDVI — the type must not reject them.
        CellEnvironment waterCell = new CellEnvironment(
                -0.15f, -0.10f, 0.0f, 0.0f, VegetationTypeEnum.WATER
        );
        assertEquals(-0.15f, waterCell.getNdvi(), 1e-5f);
    }

    @Test
    void zeroSlope_flatTerrain_isValid() {
        CellEnvironment flatCell = new CellEnvironment(
                0.50f, 0.30f, 0.0f, 0.0f, VegetationTypeEnum.MONTANE_GRASSLAND
        );
        assertEquals(0.0f, flatCell.getSlopeRadians(), 1e-10f);
    }
}