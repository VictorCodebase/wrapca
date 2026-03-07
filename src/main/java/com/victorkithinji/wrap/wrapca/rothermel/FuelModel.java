package com.victorkithinji.wrap.wrapca.rothermel;

/**
 * Immutable fuel parameter bundle for one vegetation type.
 *
 * <p>All values are in SI units consistent with the simplified Rothermel (1972) formulation
 * used by {@link RothermelRosCalculator}:
 * <ul>
 *   <li>{@code ovendryFuelLoad}     — kg m⁻²</li>
 *   <li>{@code moistureOfExtinction} — dimensionless fraction (e.g. 0.25 = 25 %)</li>
 *   <li>{@code heatContent}          — kJ kg⁻¹</li>
 *   <li>{@code savRatio}             — m² m⁻³  (surface-area-to-volume ratio)</li>
 * </ul>
 */
public record FuelModel(
        double ovendryFuelLoad,
        double moistureOfExtinction,
        double heatContent,
        double savRatio
) {}