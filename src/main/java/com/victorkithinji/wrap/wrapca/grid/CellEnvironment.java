package com.victorkithinji.wrap.wrapca.grid;

import lombok.Value;

/**
 * Immutable environmental state vector for a single CA grid cell.
 *
 * <p>This object is assigned at grid initialisation from the CV GeoTIFF
 * output and remains fixed for the lifetime of a simulation run unless
 * explicitly refreshed by a CV correction step
 * ({@code correction.CvStateInjectorService}).
 *
 * <p>All values are stored in the units specified per field to keep the
 * Rothermel physics layer free of unit-conversion logic.
 *
 * <p>Instances are created via the Lombok-generated all-args constructor:
 * <pre>{@code
 *   CellEnvironment env = new CellEnvironment(
 *       0.65f,           // ndvi
 *       0.40f,           // ndmi
 *       1850.0f,         // elevationMetres
 *       0.174533f,       // slopeRadians  (~10°)
 *       1.5707963f,      // aspectRadians (~90° East)
 *       VegetationTypeEnum.AFROMONTANE_FOREST
 *   );
 * }</pre>
 */
@Value
public class CellEnvironment {

    /**
     * Normalised Difference Vegetation Index, range [-1, 1].
     *
     * <p>Used as a proxy for fuel load in the Rothermel layer.
     * Indicative ranges for East African contexts:
     * <ul>
     *   <li>0.6–0.8 — dense, healthy canopy (high fuel load)</li>
     *   <li>0.2–0.3 — shrub or grassland (moderate fuel load)</li>
     *   <li>0.0–0.1 — bare soil / rock (minimal fuel load)</li>
     * </ul>
     */
    float ndvi;

    /**
     * Normalised Difference Moisture Index, range [-1, 1].
     *
     * <p>Used as a proxy for live fuel moisture content (LFMC).
     * Higher NDMI indicates wetter vegetation and suppresses ignition
     * probability. Refreshed for UNBURNED cells at each CV correction step.
     */
    float ndmi;

    /**
     * Terrain elevation above sea level in <strong>metres</strong>.
     *
     * <p>Sourced from the Copernicus DEM GLO-30 (30 m) resampled to the
     * CA grid resolution by {@code ingestion.RasterResamplerService}.
     * Stored here so that {@code simulation.IgnitionProbabilityResolver}
     * can call {@code rothermel.SlopeEffectCalculator.slopeAngleRadians()}
     * directly with source and target elevations rather than approximating
     * slope via the aspect-projection workaround (DEV-006 — closed).
     */
    float elevationMetres;

    /**
     * Terrain slope magnitude in <strong>radians</strong>, range [0, π/2].
     *
     * <p>Derived from the Copernicus DEM GLO-30 (30 m) resampled to the
     * 100 m CA grid. Used by {@code rothermel.SlopeEffectCalculator} to
     * compute the slope factor φs for uphill spread acceleration.
     */
    float slopeRadians;

    /**
     * Terrain aspect (slope-facing direction) in <strong>radians</strong>,
     * measured clockwise from north, range [0, 2π].
     *
     * <p>Combined with wind direction in the Rothermel layer to resolve the
     * effective uphill component relative to each Moore spread direction.
     */
    float aspectRadians;

    /**
     * Vegetation classification for this cell, used as the lookup key into
     * the East Africa fuel model table by
     * {@code rothermel.FuelModelResolver}.
     *
     * <p>Cells carrying {@link VegetationTypeEnum#WATER} or
     * {@link VegetationTypeEnum#BUILT} will be set to
     * {@link CellStateEnum#NON_COMBUSTIBLE} at grid initialisation and this
     * field is therefore irrelevant for those cells at runtime.
     */
    VegetationTypeEnum vegetationType;
}