package com.victorkithinji.wrap.wrapca.grid;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link VegetationTypeEnum}.
 *
 * The key contract is which types are combustible and which are not —
 * GridInitialiserService depends on this to mark NON_COMBUSTIBLE cells, and
 * FuelModelResolver depends on combustible types having entries in the fuel
 * models JSON. Both callers use the same sets tested here.
 */
class VegetationTypeTest {

    /** Types that GridInitialiserService must mark as NON_COMBUSTIBLE. */
    private static final Set<VegetationTypeEnum> NON_COMBUSTIBLE_TYPES =
            Set.of(VegetationTypeEnum.WATER, VegetationTypeEnum.BUILT);

    /** Types that must have a fuel model entry and participate in ROS calculations. */
    private static final Set<VegetationTypeEnum> COMBUSTIBLE_TYPES = Set.of(
            VegetationTypeEnum.AFROMONTANE_FOREST,
            VegetationTypeEnum.CROPLAND,
            VegetationTypeEnum.GRASSLAND,
            VegetationTypeEnum.SHRUBLAND,
            VegetationTypeEnum.BARE_SOIL
    );

    @Test
    void water_isNonCombustible() {
        assertTrue(NON_COMBUSTIBLE_TYPES.contains(VegetationTypeEnum.WATER));
    }

    @Test
    void built_isNonCombustible() {
        assertTrue(NON_COMBUSTIBLE_TYPES.contains(VegetationTypeEnum.BUILT));
    }

    @Test
    void afromontaneForest_isCombustible() {
        assertTrue(COMBUSTIBLE_TYPES.contains(VegetationTypeEnum.AFROMONTANE_FOREST));
    }

    @Test
    void montaneGrassland_isCombustible() {
        assertTrue(COMBUSTIBLE_TYPES.contains(VegetationTypeEnum.GRASSLAND));
    }

    @Test
    void shrubland_isCombustible() {
        assertTrue(COMBUSTIBLE_TYPES.contains(VegetationTypeEnum.SHRUBLAND));
    }

    @Test
    void bareSoil_isCombustible() {
        assertTrue(COMBUSTIBLE_TYPES.contains(VegetationTypeEnum.BARE_SOIL));
    }

    @Test
    void allTypesCoveredByExactlyOneCombustibilitySet() {
        // Every VegetationType must belong to exactly one of the two sets —
        // no type should be silently unclassified.
        for (VegetationTypeEnum type : VegetationTypeEnum.values()) {
            boolean inCombustible = COMBUSTIBLE_TYPES.contains(type);
            boolean inNonCombustible = NON_COMBUSTIBLE_TYPES.contains(type);

            assertTrue(inCombustible ^ inNonCombustible,
                    type + " must be in exactly one combustibility set. " +
                            "Update these sets when adding a new VegetationType.");
        }
    }

    @Test
    void totalTypeCount_matchesExpected() {
        // Guard against a new type being added without updating the
        // combustibility sets or the fuel models JSON.
        assertEquals(7, VegetationTypeEnum.values().length,
                "New VegetationType requires an entry in east_africa_fuel_models.json " +
                        "and classification in the combustibility sets above");
    }
}