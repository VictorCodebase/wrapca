package com.victorkithinji.wrap.wrapca.rothermel;

/**
 * Computes the slope angle φ_s between a burning source cell and its target
 * neighbour, for use as the terrain input to {@link RothermelRosCalculator}.
 *
 * <h2>Sign convention</h2>
 * <ul>
 *   <li>Positive φ_s — target cell is uphill from source (fire spreading upslope,
 *       which accelerates ROS).</li>
 *   <li>Negative φ_s — target cell is downhill (fire spreading downslope).</li>
 *   <li>Zero — flat terrain.</li>
 * </ul>
 * {@link RothermelRosCalculator} uses {@code tan(φ_s)} directly; only positive
 * values contribute the Rothermel slope factor φ_w (downslope spread is not
 * accelerated by the slope term in the standard formulation).
 *
 * <h2>Distance convention</h2>
 * Pass the appropriate planar distance for the relationship between source and
 * target:
 * <ul>
 *   <li>Cardinal neighbours (N, E, S, W) — {@code cellSizeMetres}</li>
 *   <li>Diagonal neighbours (NE, SE, SW, NW) — {@code cellSizeMetres × √2}</li>
 * </ul>
 */
public final class SlopeEffectCalculator {

    // Utility class — not instantiated
    private SlopeEffectCalculator() {}

    /**
     * Returns the slope angle φ_s (radians) from source cell toward target cell.
     *
     * @param sourceElevationM elevation of the burning source cell (metres)
     * @param targetElevationM elevation of the target (potentially igniting) cell
     *                         (metres)
     * @param distanceM        planar cell-centre-to-cell-centre distance in metres;
     *                         use {@code cellSize} for cardinals,
     *                         {@code cellSize × √2} for diagonals
     * @return slope angle in radians; positive = upslope, negative = downslope
     * @throws IllegalArgumentException if {@code distanceM} ≤ 0
     */
    public static double slopeAngleRadians(
            double sourceElevationM,
            double targetElevationM,
            double distanceM
    ) {
        if (distanceM <= 0.0) {
            throw new IllegalArgumentException(
                    "distanceM must be positive, got: " + distanceM);
        }
        double deltaZ = targetElevationM - sourceElevationM;
        return Math.atan(deltaZ / distanceM);
    }

    /**
     * Convenience overload: infers the correct planar distance from the
     * {@code isDiagonal} flag.
     *
     * @param sourceElevationM elevation of the burning source cell (metres)
     * @param targetElevationM elevation of the target cell (metres)
     * @param cellSizeMetres   CA grid cell size (metres)
     * @param isDiagonal       {@code true} for NE/SE/SW/NW neighbours
     * @return slope angle in radians
     */
    public static double slopeAngleRadians(
            double sourceElevationM,
            double targetElevationM,
            double cellSizeMetres,
            boolean isDiagonal
    ) {
        double distance = isDiagonal ? cellSizeMetres * Math.sqrt(2) : cellSizeMetres;
        return slopeAngleRadians(sourceElevationM, targetElevationM, distance);
    }
}