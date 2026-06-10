package com.victorkithinji.wrap.wrapca.rothermel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.victorkithinji.wrap.wrapca.grid.VegetationTypeEnum;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;

/**
 * Maps {@link VegetationTypeEnum} → {@link FuelModel}.
 *
 * <p>The lookup table is loaded once from
 * {@code resources/fuelmodels/east_africa_fuel_models.json} at class-initialisation
 * time.  After that every call to {@link #resolve(VegetationTypeEnum)} is a single
 * {@code EnumMap} lookup — no I/O, no Spring context required.
 *
 * <p>If the JSON cannot be read the static initialiser throws
 * {@link ExceptionInInitializerError}, which will surface immediately on first use
 * rather than producing silent wrong results at runtime.
 */
public final class FuelModelResolver {

    // -------------------------------------------------------------------------
    // Static lookup table — built once, shared across all callers
    // -------------------------------------------------------------------------

    private static final Map<VegetationTypeEnum, FuelModel> TABLE;

    static {
        TABLE = loadFromJson();
    }

    // Private constructor: utility class, never instantiated
    private FuelModelResolver() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns the {@link FuelModel} for the given vegetation type.
     *
     * @param type the vegetation type of the cell
     * @return the corresponding fuel parameters (never null)
     * @throws IllegalArgumentException if the type has no entry in the table
     *         (indicates a JSON / enum mismatch that must be fixed)
     */
    public static FuelModel resolve(VegetationTypeEnum type) {
        FuelModel model = TABLE.get(type);
        if (model == null) {
            throw new IllegalArgumentException(
                    "No fuel model found for VegetationType: " + type
                            + ". Ensure east_africa_fuel_models.json contains an entry for every enum value.");
        }
        return model;
    }

    // -------------------------------------------------------------------------
    // JSON loading
    // -------------------------------------------------------------------------

    private static Map<VegetationTypeEnum, FuelModel> loadFromJson() {
        ObjectMapper mapper = new ObjectMapper();
        String resourcePath = "/fuelmodels/east_africa_fuel_models.json";

        try (InputStream is = FuelModelResolver.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalStateException(
                        "Fuel model resource not found on classpath: " + resourcePath);
            }

            JsonNode root = mapper.readTree(is);
            JsonNode fuelModels = root.get("fuelModels");
            if (fuelModels == null) {
                throw new IllegalStateException(
                        "east_africa_fuel_models.json is missing top-level 'fuelModels' key");
            }

            Map<VegetationTypeEnum, FuelModel> table = new EnumMap<>(VegetationTypeEnum.class);

            for (VegetationTypeEnum vegType : VegetationTypeEnum.values()) {
                JsonNode node = fuelModels.get(vegType.name());
                if (node == null) {
                    throw new IllegalStateException(
                            "east_africa_fuel_models.json has no entry for VegetationType: " + vegType.name());
                }
                table.put(vegType, new FuelModel(
                        node.get("ovendryFuelLoad_kg_m2").asDouble(),
                        node.get("moistureOfExtinction_fraction").asDouble(),
                        node.get("heatContent_kJ_kg").asDouble(),
                        node.get("savRatio_m2_m3").asDouble()
                ));
            }

            return table;

        } catch (IOException e) {
            throw new ExceptionInInitializerError(
                    "Failed to load east_africa_fuel_models.json: " + e.getMessage());
        }
    }
}