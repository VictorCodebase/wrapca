package com.victorkithinji.wrap.wrapca.rothermel;

/**
 * Computes the Rothermel (1972) surface fire Rate of Spread (ROS) in
 * metres per minute.
 *
 * <h2>Formulation used</h2>
 * Simplified Rothermel as presented in Andrews (2018) RMRS-GTR-371:
 *
 * <pre>
 *   R = (I_R × ξ × (1 + φ_w + φ_s)) / (ρ_b × ε × Q_ig)
 * </pre>
 *
 * <h2>Unit discipline</h2>
 * All empirical sub-formulae were fitted in US customary units (ft, lb, BTU, min).
 * Conversions are applied at the boundary of each sub-method; callers always
 * work in SI.
 *
 * <h2>Constraints</h2>
 * <ul>
 *   <li>Zero Spring annotations — unit-testable in isolation.</li>
 *   <li>All methods static — no instance state.</li>
 *   <li>Moisture damping clamps ROS to zero when moisture ≥ extinction.</li>
 * </ul>
 */
public final class RothermelRosCalculator {

    // -------------------------------------------------------------------------
    // Fixed physical constants
    // -------------------------------------------------------------------------

    /** Fuel bed depth (m) — used for bulk density. */
    private static final double FUEL_BED_DEPTH_M = 0.3;

    /** Mineral damping coefficient η_s — Rothermel (1972) standard value. */
    private static final double MINERAL_DAMPING = 0.41739;

    /** Oven-dry particle density (kg m⁻³) — typical dry wood / grass. */
    private static final double PARTICLE_DENSITY_KG_M3 = 513.0;

    /**
     * Midflame wind adjustment factor for open grassland / shrubland.
     * ERA5 and most NWP products report 10 m wind speed.  The Rothermel wind
     * factor φ_w requires midflame wind, which sits within the flame zone
     * (typically 0.3–1 m above ground).  Andrews (2018) §4.2 gives a
     * representative open-terrain factor of 0.4 (i.e. midflame ≈ 40 % of
     * the 10 m value).  This is applied inside {@link #windFactor} so that
     * callers always pass the 10 m wind speed from the wind field loader.
     */
    private static final double MIDFLAME_WIND_FACTOR = 0.4;

    private RothermelRosCalculator() {}

    // =========================================================================
    // Primary entry point
    // =========================================================================

    /**
     * Computes ROS (m min⁻¹).
     *
     * @param fuel            fuel model for the source cell's vegetation type
     * @param fuelMoisture    live fuel moisture as a dimensionless fraction
     *                        (e.g. 0.10 = 10 %), proxied by NDMI in the CA engine
     * @param effectiveWindMs effective wind component toward the target cell (m s⁻¹),
     *                        from {@link WindProjectionCalculator#effectiveComponent}
     * @param slopeAngleRad   slope angle toward the target cell (radians), from
     *                        {@link SlopeEffectCalculator#slopeAngleRadians};
     *                        negative (downslope) values contribute zero
     * @return rate of spread in metres per minute (≥ 0)
     */
    public static double computeRos(
            FuelModel fuel,
            double fuelMoisture,
            double effectiveWindMs,
            double slopeAngleRad
    ) {
        // 1. Moisture damping — zero ROS if fuel is too wet to sustain combustion
        double moistureRatio = fuelMoisture / fuel.moistureOfExtinction();
        if (moistureRatio >= 1.0) {
            return 0.0;
        }
        double etaM = moistureDamping(moistureRatio);

        // 2. Reaction intensity I_R (kW m⁻²)
        double reactionIntensity = reactionIntensity(fuel, etaM);
        if (reactionIntensity <= 0.0) {
            return 0.0;
        }

        // 3. Packing ratio and propagating flux ratio ξ
        double beta = packingRatio(fuel);
        double xi   = propagatingFluxRatio(fuel.savRatio(), beta);

        // 4. Wind factor φ_w (dimensionless, ≥ 0)
        double phiWind  = windFactor(effectiveWindMs, fuel.savRatio(), beta);

        // 5. Slope factor φ_s (dimensionless, ≥ 0)
        double phiSlope = slopeFactor(slopeAngleRad, beta);

        // 6. Heat sink: ρ_b × ε × Q_ig  (kJ m⁻³)
        double bulkDensity = fuel.ovendryFuelLoad() / FUEL_BED_DEPTH_M;
        double epsilon     = effectiveHeatingNumber(fuel.savRatio());
        double qig         = heatOfPreIgnition(fuelMoisture);
        double heatSink    = bulkDensity * epsilon * qig;
        if (heatSink <= 0.0) {
            return 0.0;
        }

        // 7. ROS (m min⁻¹)
        //    I_R (kW m⁻²) = kJ s⁻¹ m⁻²
        //    heat sink (kJ m⁻³)
        //    → R in m s⁻¹ × 60 = m min⁻¹
        double rosMetresPerMin = (reactionIntensity * xi * (1.0 + phiWind + phiSlope))
                / heatSink
                * 60.0;

        return Math.max(0.0, rosMetresPerMin);
    }

    // =========================================================================
    // Sub-calculations — package-private for unit testing
    // =========================================================================

    /**
     * Moisture damping coefficient η_M — Rothermel (1972) Eq. 29.
     *
     * @param moistureRatio m_f / m_x, in [0, 1)
     */
    static double moistureDamping(double moistureRatio) {
        double r = Math.min(moistureRatio, 0.9999);
        return 1.0 - 2.59 * r + 5.11 * r * r - 3.52 * r * r * r;
    }

    /**
     * Optimum packing ratio β_op — Andrews (2018) Eq. 37.
     *
     * @param savRatio σ in m⁻¹
     */
    static double optimumPackingRatio(double savRatio) {
        double sigma = savRatio * 0.3048;   // m⁻¹ → ft⁻¹
        return 3.348 / Math.pow(sigma, 0.8189);
    }

    /**
     * A-coefficient for the Γ' exponent — Andrews (2018) Eq. 38.
     *
     * @param savRatio σ in m⁻¹
     */
    static double aCoefficient(double savRatio) {
        double sigma = savRatio * 0.3048;
        return 133.0 / Math.pow(sigma, 0.7913);
    }

    /**
     * Maximum reaction velocity Γ'_max (min⁻¹) — Andrews (2018) Eq. 36.
     *
     * @param savRatio σ in m⁻¹
     */
    static double maxReactionVelocity(double savRatio) {
        double sigma = savRatio * 0.3048;
        return Math.pow(sigma, 1.5) / (495.0 + 0.0594 * Math.pow(sigma, 1.5));
    }

    /**
     * Reaction intensity I_R (kW m⁻²) — Rothermel (1972) Eq. 27.
     *
     * <p>Full Γ' = Γ'_max × (β/β_op)^A × exp(A × (1 − β/β_op)) is evaluated
     * here so the actual cell packing ratio contributes.
     *
     * @param fuel           fuel model
     * @param moistureDamping η_M from {@link #moistureDamping}
     */
    static double reactionIntensity(FuelModel fuel, double moistureDamping) {
        double betaOp   = optimumPackingRatio(fuel.savRatio());
        double A        = aCoefficient(fuel.savRatio());
        double gammaMax = maxReactionVelocity(fuel.savRatio());
        double beta     = packingRatio(fuel);

        double ratio = beta / betaOp;
        // Γ' = Γ'_max × (β/β_op)^A × exp(A × (1 − β/β_op))
        double gamma = gammaMax * Math.pow(ratio, A) * Math.exp(A * (1.0 - ratio));

        // Fuel load: kg m⁻² → lb ft⁻²  (1 kg m⁻² = 0.2048 lb ft⁻²)
        double loadLbFt2 = fuel.ovendryFuelLoad() * 0.2048;

        // Heat content: kJ kg⁻¹ → BTU lb⁻¹  (1 kJ kg⁻¹ = 0.4299 BTU lb⁻¹)
        double heatBtu = fuel.heatContent() * 0.4299;

        // I_R in BTU ft⁻² min⁻¹, then → kW m⁻²  (×0.18921)
        double irBtu = gamma * loadLbFt2 * heatBtu * moistureDamping * MINERAL_DAMPING;
        return irBtu * 0.18921;
    }

    /**
     * Fuel bed packing ratio β (dimensionless) — Rothermel (1972) Eq. 31.
     */
    static double packingRatio(FuelModel fuel) {
        return fuel.ovendryFuelLoad() / (FUEL_BED_DEPTH_M * PARTICLE_DENSITY_KG_M3);
    }

    /**
     * Propagating flux ratio ξ — Rothermel (1972) Eq. 42. Arithmetic in ft⁻¹.
     *
     * @param savRatio σ in m⁻¹
     * @param beta     packing ratio
     */
    static double propagatingFluxRatio(double savRatio, double beta) {
        double sigma = savRatio * 0.3048;
        return Math.exp((0.792 + 0.681 * Math.sqrt(sigma)) * (beta + 0.1))
                / (192.0 + 0.2595 * sigma);
    }

    /**
     * Wind factor φ_w — Rothermel (1972) Eq. 47. Wind converted m s⁻¹ → ft min⁻¹.
     *
     * @param windMs   effective wind speed toward target cell (m s⁻¹, ≥ 0)
     * @param savRatio σ in m⁻¹
     * @param beta     packing ratio
     */
    static double windFactor(double windMs, double savRatio, double beta) {
        if (windMs <= 0.0) return 0.0;
        double sigma     = savRatio * 0.3048;
        double betaOp    = optimumPackingRatio(savRatio);
        // Convert 10 m wind to midflame wind before applying Rothermel empirical coefficients.
        double windFtMin = (windMs * MIDFLAME_WIND_FACTOR) * 196.85;
        double C = 7.47   * Math.exp(-0.133  * Math.pow(sigma, 0.55));
        double B = 0.02526 * Math.pow(sigma, 0.54);
        double E = 0.715   * Math.exp(-3.59e-4 * sigma);
        return C * Math.pow(windFtMin, B) * Math.pow(beta / betaOp, -E);
    }

    /**
     * Slope factor φ_s — Rothermel (1972) Eq. 51. Downslope → zero.
     *
     * @param slopeAngleRad radians; positive = upslope
     * @param beta          packing ratio
     */
    static double slopeFactor(double slopeAngleRad, double beta) {
        if (slopeAngleRad <= 0.0) return 0.0;
        double tanPhi = Math.tan(slopeAngleRad);
        return 5.275 * Math.pow(beta, -0.3) * tanPhi * tanPhi;
    }

    /**
     * Effective heating number ε — Rothermel (1972) Eq. 14. Arithmetic in ft⁻¹.
     *
     * @param savRatio σ in m⁻¹
     */
    static double effectiveHeatingNumber(double savRatio) {
        double sigma = savRatio * 0.3048;
        return Math.exp(-138.0 / sigma);
    }

    /**
     * Heat of pre-ignition Q_ig (kJ kg⁻¹) — Rothermel (1972) Eq. 12.
     * Fitted in BTU lb⁻¹, converted on output.
     *
     * @param fuelMoisture dimensionless fraction
     */
    static double heatOfPreIgnition(double fuelMoisture) {
        double qBtu = 250.0 + 1116.0 * fuelMoisture;
        return qBtu * 2.326;   // BTU lb⁻¹ → kJ kg⁻¹
    }
}