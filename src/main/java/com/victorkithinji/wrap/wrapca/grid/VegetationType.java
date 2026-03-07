package com.victorkithinji.wrap.wrapca.grid;

//TODO: Add a fuelmodels/east_africa_fuel_models.json to ap veg types to fuel models
/**
 * Vegetation classification for a CA grid cell, derived from the CV
 * fuel-state map (Sentinel-2 classification output).
 *
 * <p>Each constant maps to a set of Rothermel fuel parameters loaded from
 * {@code fuelmodels/east_africa_fuel_models.json} by
 * {@code rothermel.FuelModelResolver}. The resolver treats this enum as the
 * lookup key — adding a new type here requires a matching entry in that JSON
 * file before it can participate in ROS calculations.
 *
 * <p>{@code WATER} and {@code BUILT} are non-combustible by definition;
 * cells carrying these types are initialised as
 * {@link CellState#NON_COMBUSTIBLE} and are never evaluated by the engine.
 */
public enum VegetationType {

    /**
     * Dense highland forest: Afromontane evergreen canopy typical of
     * Aberdare, Mt Kenya, and Mau Forest zones.
     * High fuel load, relatively high live moisture content.
     */
    AFROMONTANE_FOREST,

    /**
     * Open montane grassland and moorland above the forest line.
     * Lower fuel load than forest but fast-drying; elevated ROS under wind.
     */
    MONTANE_GRASSLAND,

    /**
     * Mixed shrubland and bushed grassland at mid-altitude transitions.
     * Intermediate fuel load and moisture regime.
     */
    SHRUBLAND,

    /**
     * Bare soil, exposed rock, or sparsely vegetated ground.
     * Minimal fuel load; ROS will be very low but not zero — residual litter
     * may still carry fire slowly.
     */
    BARE_SOIL,

    /**
     * Open water (lakes, rivers, wetlands).
     * Non-combustible — cells of this type are set to
     * {@link CellState#NON_COMBUSTIBLE} at grid initialisation.
     */
    WATER,

    /**
     * Built-up / urban / infrastructure land cover (roads, buildings,
     * settlements derived from OSM).
     * Non-combustible — cells of this type are set to
     * {@link CellState#NON_COMBUSTIBLE} at grid initialisation.
     */
    BUILT
}