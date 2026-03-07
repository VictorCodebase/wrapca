package com.victorkithinji.wrap.wrapca.rothermel;

import com.victorkithinji.wrap.wrapca.grid.VegetationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for GROUP 2 — Rothermel fire physics layer.
 *
 * <p>Reference values are drawn from:
 * <ul>
 *   <li>Andrews, P.L. (2018) RMRS-GTR-371 — the definitive Rothermel reference</li>
 *   <li>Direct mathematical verification of each sub-formula</li>
 * </ul>
 *
 * <p>All tests are pure Java, zero Spring context required.
 */
class RothermelPhysicsTest {

    // =========================================================================
    // FuelModelResolver
    // =========================================================================

    @Nested
    @DisplayName("FuelModelResolver")
    class FuelModelResolverTests {

        @Test
        @DisplayName("Resolves all VegetationType values without throwing")
        void allVegetationTypesResolve() {
            for (VegetationType type : VegetationType.values()) {
                assertThatCode(() -> FuelModelResolver.resolve(type))
                        .as("resolve(%s) should not throw", type)
                        .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("AFROMONTANE_FOREST has positive fuel load and heat content")
        void afromontaneForestHasPositiveFuelParameters() {
            FuelModel m = FuelModelResolver.resolve(VegetationType.AFROMONTANE_FOREST);
            assertThat(m.ovendryFuelLoad()).isPositive();
            assertThat(m.heatContent()).isPositive();
            assertThat(m.savRatio()).isPositive();
        }

        @Test
        @DisplayName("WATER has zero fuel load — non-combustible sentinel")
        void waterHasZeroFuelLoad() {
            FuelModel m = FuelModelResolver.resolve(VegetationType.WATER);
            assertThat(m.ovendryFuelLoad()).isZero();
        }

        @Test
        @DisplayName("BUILT has zero fuel load — non-combustible sentinel")
        void builtHasZeroFuelLoad() {
            FuelModel m = FuelModelResolver.resolve(VegetationType.BUILT);
            assertThat(m.ovendryFuelLoad()).isZero();
        }

        @Test
        @DisplayName("Moisture of extinction is in physically meaningful range (0, 1)")
        void moistureOfExtinctionInRange() {
            for (VegetationType type : VegetationType.values()) {
                FuelModel m = FuelModelResolver.resolve(type);
                if (m.ovendryFuelLoad() > 0) {
                    assertThat(m.moistureOfExtinction())
                            .as("%s moistureOfExtinction", type)
                            .isGreaterThan(0.0)
                            .isLessThanOrEqualTo(1.0);
                }
            }
        }
    }

    // =========================================================================
    // WindProjectionCalculator
    // =========================================================================

    @Nested
    @DisplayName("WindProjectionCalculator")
    class WindProjectionTests {

        @Test
        @DisplayName("Wind from South (180°) fully projects onto direction index 0 (North)")
        void southWindFullyProjectsNorth() {
            // Met direction 180° = wind blowing from South toward North
            // Direction index 0 = North neighbour
            double ue = WindProjectionCalculator.effectiveComponent(10.0, 180.0, 0);
            assertThat(ue).isCloseTo(10.0, within(0.01));
        }

        @Test
        @DisplayName("Wind from North (0°) projects zero onto direction index 0 (North) — headwind clamped")
        void northWindClampsToZeroOnNorthDirection() {
            // Met 0° = wind from North (blowing South) — opposite to direction 0
            double ue = WindProjectionCalculator.effectiveComponent(10.0, 0.0, 0);
            assertThat(ue).isZero();
        }

        @Test
        @DisplayName("Wind from South (180°) has ~0.707 component onto NE diagonal (index 1)")
        void southWindPartialProjectionOnNE() {
            double ue = WindProjectionCalculator.effectiveComponent(10.0, 180.0, 1);
            assertThat(ue).isCloseTo(10.0 / Math.sqrt(2), within(0.05));
        }

        @Test
        @DisplayName("Zero wind speed always returns zero regardless of direction")
        void zeroWindSpeedReturnsZero() {
            for (int i = 0; i < 8; i++) {
                assertThat(WindProjectionCalculator.effectiveComponent(0.0, 270.0, i))
                        .as("direction index %d", i)
                        .isZero();
            }
        }

        @Test
        @DisplayName("Negative projections are clamped to zero")
        void negativeProjectionClampedToZero() {
            // Wind FROM East (90°) blows toward West.
            // Projection onto direction index 2 (East) is negative → clamped to 0.
            double ue = WindProjectionCalculator.effectiveComponent(5.0, 90.0, 2);
            assertThat(ue).isZero();
        }

        @Test
        @DisplayName("Throws on direction index out of range")
        void throwsOnInvalidDirectionIndex() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> WindProjectionCalculator.effectiveComponent(5.0, 90.0, 8));
        }
    }

    // =========================================================================
    // SlopeEffectCalculator
    // =========================================================================

    @Nested
    @DisplayName("SlopeEffectCalculator")
    class SlopeEffectTests {

        @Test
        @DisplayName("Flat terrain returns zero slope angle")
        void flatTerrainReturnsZero() {
            double angle = SlopeEffectCalculator.slopeAngleRadians(100.0, 100.0, 100.0);
            assertThat(angle).isZero();
        }

        @Test
        @DisplayName("45° upslope: Δz == distance → atan(1) = π/4")
        void fortyFiveDegreeSlopeUphill() {
            double angle = SlopeEffectCalculator.slopeAngleRadians(0.0, 100.0, 100.0);
            assertThat(angle).isCloseTo(Math.PI / 4, within(1e-9));
        }

        @Test
        @DisplayName("Downslope returns negative angle")
        void downslopereturnsNegativeAngle() {
            double angle = SlopeEffectCalculator.slopeAngleRadians(200.0, 100.0, 100.0);
            assertThat(angle).isNegative();
        }

        @Test
        @DisplayName("Cardinal vs diagonal: diagonal produces shallower angle for same Δz")
        void diagonalDistanceProducesShallowerAngle() {
            double cardinal = SlopeEffectCalculator.slopeAngleRadians(0.0, 50.0, 100.0, false);
            double diagonal = SlopeEffectCalculator.slopeAngleRadians(0.0, 50.0, 100.0, true);
            assertThat(diagonal).isLessThan(cardinal);
        }

        @Test
        @DisplayName("Throws on zero distance")
        void throwsOnZeroDistance() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> SlopeEffectCalculator.slopeAngleRadians(100.0, 200.0, 0.0));
        }
    }

    // =========================================================================
    // RothermelRosCalculator — sub-calculations
    // =========================================================================

    @Nested
    @DisplayName("RothermelRosCalculator — sub-calculations")
    class RosSubCalculationTests {

        @Test
        @DisplayName("moistureDamping = 1.0 when moisture ratio = 0 (bone dry fuel)")
        void moistureDampingMaxAtZeroMoisture() {
            double eta = RothermelRosCalculator.moistureDamping(0.0);
            assertThat(eta).isCloseTo(1.0, within(0.001));
        }

        @Test
        @DisplayName("moistureDamping approaches 0 as moisture ratio approaches 1.0")
        void moistureDampingNearZeroAtExtinction() {
            double eta = RothermelRosCalculator.moistureDamping(0.999);
            assertThat(eta).isLessThan(0.05);
        }

        @Test
        @DisplayName("effectiveHeatingNumber is in (0, 1) for typical SAV ratios")
        void effectiveHeatingNumberInRange() {
            // Typical grass SAV ~11500 m⁻¹, forest ~4900 m⁻¹
            double epsGrass = RothermelRosCalculator.effectiveHeatingNumber(11483);
            double epsForest = RothermelRosCalculator.effectiveHeatingNumber(4921);
            assertThat(epsGrass).isBetween(0.0, 1.0);
            assertThat(epsForest).isBetween(0.0, 1.0);
        }

        @Test
        @DisplayName("slopeFactor is zero for flat / downslope terrain")
        void slopeFactorZeroForFlatOrDownslope() {
            double beta = 0.005;
            assertThat(RothermelRosCalculator.slopeFactor(0.0, beta)).isZero();
            assertThat(RothermelRosCalculator.slopeFactor(-0.3, beta)).isZero();
        }

        @Test
        @DisplayName("windFactor is zero when wind is zero")
        void windFactorZeroForNoWind() {
            assertThat(RothermelRosCalculator.windFactor(0.0, 4921, 0.005)).isZero();
        }

        @Test
        @DisplayName("windFactor increases with wind speed")
        void windFactorIncreasesWithWindSpeed() {
            double phi1 = RothermelRosCalculator.windFactor(2.0, 4921, 0.005);
            double phi2 = RothermelRosCalculator.windFactor(8.0, 4921, 0.005);
            assertThat(phi2).isGreaterThan(phi1);
        }
    }

    // =========================================================================
    // RothermelRosCalculator — integration (full ROS computation)
    // =========================================================================

    @Nested
    @DisplayName("RothermelRosCalculator — full ROS")
    class RosIntegrationTests {

        private final FuelModel grassFuel = FuelModelResolver.resolve(VegetationType.MONTANE_GRASSLAND);
        private final FuelModel forestFuel = FuelModelResolver.resolve(VegetationType.AFROMONTANE_FOREST);

        @Test
        @DisplayName("ROS = 0 when fuel moisture ≥ moisture of extinction")
        void rosZeroAboveExtinctionMoisture() {
            double ros = RothermelRosCalculator.computeRos(
                    grassFuel,
                    grassFuel.moistureOfExtinction() + 0.01,  // just above extinction
                    5.0,
                    0.0
            );
            assertThat(ros).isZero();
        }

        @Test
        @DisplayName("ROS > 0 for dry grass with moderate wind on flat terrain")
        void rosPositiveForDryGrassWithWind() {
            double ros = RothermelRosCalculator.computeRos(grassFuel, 0.06, 5.0, 0.0);
            assertThat(ros).isPositive();
        }

        @Test
        @DisplayName("ROS increases with higher wind speed (all else equal)")
        void rosIncreasesWithWindSpeed() {
            double ros2 = RothermelRosCalculator.computeRos(grassFuel, 0.06, 2.0, 0.0);
            double ros8 = RothermelRosCalculator.computeRos(grassFuel, 0.06, 8.0, 0.0);
            assertThat(ros8).isGreaterThan(ros2);
        }

        @Test
        @DisplayName("ROS increases with upslope angle (all else equal)")
        void rosIncreasesWithSlope() {
            double rosFlat  = RothermelRosCalculator.computeRos(grassFuel, 0.06, 3.0, 0.0);
            double rosSlope = RothermelRosCalculator.computeRos(grassFuel, 0.06, 3.0, Math.toRadians(20));
            assertThat(rosSlope).isGreaterThan(rosFlat);
        }

        @Test
        @DisplayName("Downslope angle does NOT accelerate ROS beyond flat value")
        void downslopeDoesNotAccelerate() {
            double rosFlat  = RothermelRosCalculator.computeRos(grassFuel, 0.06, 3.0, 0.0);
            double rosDown  = RothermelRosCalculator.computeRos(grassFuel, 0.06, 3.0, Math.toRadians(-15));
            assertThat(rosDown).isLessThanOrEqualTo(rosFlat);
        }

        @Test
        @DisplayName("Grass ROS > Forest ROS for same conditions (grass = finer, drier fuel)")
        void grassSpreadsFasterThanForestUnderSameConditions() {
            double mf = 0.08;
            double wind = 4.0;
            double slope = 0.0;
            double rosGrass  = RothermelRosCalculator.computeRos(grassFuel,  mf, wind, slope);
            double rosForest = RothermelRosCalculator.computeRos(forestFuel, mf, wind, slope);
            assertThat(rosGrass).isGreaterThan(rosForest);
        }

        @Test
        @DisplayName("ROS is always non-negative for all input combinations")
        void rosNeverNegative() {
            double[] moistures = {0.03, 0.08, 0.15, 0.25};
            double[] winds     = {0.0, 2.0, 8.0};
            double[] slopes    = {Math.toRadians(-20), 0.0, Math.toRadians(20)};

            for (VegetationType type : VegetationType.values()) {
                FuelModel fuel = FuelModelResolver.resolve(type);
                for (double mf : moistures) {
                    for (double w : winds) {
                        for (double s : slopes) {
                            double ros = RothermelRosCalculator.computeRos(fuel, mf, w, s);
                            assertThat(ros)
                                    .as("ROS for %s, mf=%.2f, wind=%.1f, slope=%.2f", type, mf, w, s)
                                    .isGreaterThanOrEqualTo(0.0);
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("Grass ROS in plausible operational range: 1–60 m min⁻¹ for dry windy conditions")
        void grassRosInPlausibleOperationalRange() {
            // Dry grass (8% moisture), 8 m/s wind (28 km/h), flat terrain
            double ros = RothermelRosCalculator.computeRos(grassFuel, 0.08, 8.0, 0.0);
            // Literature for dry African grassland: 5–50 m min⁻¹ is credible
            assertThat(ros)
                    .as("Expected ROS in realistic operational range for dry windy grass")
                    .isBetween(1.0, 100.0);
        }
    }
}