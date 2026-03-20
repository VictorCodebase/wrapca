package com.victorkithinji.wrap.wrapca.grid;

/**
 * Land cover classification for a CA grid cell.
 *
 * <p>Values are resolved from ESA WorldCover class codes by
 * {@link GridInitialiserService} using {@code ingestion.EsaBandLayout.toVegetationType()}.
 *
 * <p><strong>Ordinal contract:</strong> ordinals are emitted in
 * {@code PhaseOneResultResponse.vegetationTypeOrdinals[]} and consumed by the
 * frontend for display labelling. Do not reorder existing constants. New
 * constants must be appended only.
 *
 * <p><strong>Fuel model contract:</strong> every constant must have a matching
 * entry in {@code resources/fuelmodels/east_africa_fuel_models.json}.
 * {@code FuelModelResolver} throws at runtime if an entry is absent.
 * {@code CROPLAND} uses grassland-equivalent fuel parameters (DEV-004).
 *
 * <p><strong>NON_COMBUSTIBLE assignment:</strong> combustibility is not
 * determined by this enum alone. {@link GridInitialiserService} marks cells
 * NON_COMBUSTIBLE when the ESA code is WATER (80), BUILT (50), or one of the
 * outside-deployment-area codes (70, 95) — regardless of what
 * {@code VegetationType} those codes resolve to. Consult that service for the
 * full rule.
 */
public enum VegetationTypeEnum {

    /** Dense Afromontane canopy. High fuel load, elevated moisture. */
    AFROMONTANE_FOREST,

    /**
     * Open grassland and savannah. Fast-drying under wind, elevated ROS.
     *
     * <p>Renamed from {@code MONTANE_GRASSLAND} — the "montane" qualifier
     * implied high-altitude ecology exclusively, which is misleading when the
     * grid extends to lower elevations (DEV-003). Fuel parameters unchanged.
     */
    GRASSLAND,

    /** Woody shrubland. Intermediate fuel load and moisture. */
    SHRUBLAND,

    /** Bare or sparsely vegetated soil. Minimal fuel load. */
    BARE_SOIL,

    /**
     * Cultivated agricultural land. Grassland-equivalent Rothermel fuel
     * parameters. Named separately from {@link #GRASSLAND} so the frontend
     * can display "Cropland" rather than "Grassland" for farmland cells
     * (DEV-004).
     */
    CROPLAND,

    /** Water body. Cells of this type are initialised as NON_COMBUSTIBLE. */
    WATER,

    /** Built-up / impervious surface. Cells of this type are initialised as NON_COMBUSTIBLE. */
    BUILT
}