package com.victorkithinji.wrap.wrapca.rothermel;

/**
 * Projects a wind vector onto one of the eight Moore neighbourhood directions.
 *
 * <h2>Direction index convention</h2>
 * The eight Moore directions are numbered 0–7 starting from North and proceeding
 * clockwise, matching the row/column delta table used throughout the CA engine:
 * <pre>
 *   0 = N   (row-1, col  )
 *   1 = NE  (row-1, col+1)
 *   2 = E   (row  , col+1)
 *   3 = SE  (row+1, col+1)
 *   4 = S   (row+1, col  )
 *   5 = SW  (row+1, col-1)
 *   6 = W   (row  , col-1)
 *   7 = NW  (row-1, col-1)
 * </pre>
 *
 * <h2>Sign convention for wind direction</h2>
 * Wind direction follows the meteorological convention: the angle (degrees) from
 * which the wind is blowing, measured clockwise from true North.  A southerly
 * wind (blowing from South → North) has direction 180°.
 *
 * <p>Internally the wind vector is converted to a unit propagation vector
 * (the direction the wind is moving <em>toward</em>) and then dot-producted
 * against the unit direction vector for each Moore cell.
 *
 * <h2>Clamping</h2>
 * Negative projections (wind blowing against the spread direction) are clamped
 * to zero.  The Rothermel wind factor φ_w is only defined for headfire conditions;
 * reduced spread in upwind directions is captured implicitly by the lower ROS
 * returned for those neighbours.
 */
public final class WindProjectionCalculator {

    /**
     * Unit vectors (dx, dy) for each Moore direction index 0–7.
     * dx = East component (+col), dy = North component (-row in grid coords).
     * Diagonals are pre-normalised to unit length.
     */
    private static final double[][] DIR_UNIT = {
            { 0.0,               1.0              },  // 0 N
            { 1.0 / Math.sqrt(2),  1.0 / Math.sqrt(2) },  // 1 NE
            { 1.0,               0.0              },  // 2 E
            { 1.0 / Math.sqrt(2), -1.0 / Math.sqrt(2) },  // 3 SE
            { 0.0,              -1.0              },  // 4 S
            {-1.0 / Math.sqrt(2), -1.0 / Math.sqrt(2) },  // 5 SW
            {-1.0,               0.0              },  // 6 W
            {-1.0 / Math.sqrt(2),  1.0 / Math.sqrt(2) }   // 7 NW
    };

    // Utility class — not instantiated
    private WindProjectionCalculator() {}

    /**
     * Returns the effective wind component U_e (m s⁻¹) along the given Moore
     * direction index.
     *
     * @param windSpeedMs      scalar wind speed in metres per second (≥ 0)
     * @param windDirectionDeg meteorological wind direction in degrees (0–360,
     *                         from which the wind is blowing, clockwise from N)
     * @param directionIndex   Moore direction index 0–7 (target cell direction
     *                         relative to the burning source cell)
     * @return effective wind component ≥ 0 (negative projections clamped to 0)
     */
    public static double effectiveComponent(
            double windSpeedMs,
            double windDirectionDeg,
            int directionIndex
    ) {
        if (directionIndex < 0 || directionIndex > 7) {
            throw new IllegalArgumentException(
                    "directionIndex must be 0–7, got: " + directionIndex);
        }

        // Convert meteorological "from" direction to "toward" propagation vector.
        // Met direction 0° = wind from N → wind moves toward S → angle 180° from North.
        double towardDeg = (windDirectionDeg + 180.0) % 360.0;
        double towardRad = Math.toRadians(towardDeg);

        // Wind propagation vector (unit length, East/North components)
        double windDx = Math.sin(towardRad);  // East component
        double windDy = Math.cos(towardRad);  // North component

        // Dot product with Moore direction unit vector
        double[] dir = DIR_UNIT[directionIndex];
        double projection = windSpeedMs * (windDx * dir[0] + windDy * dir[1]);

        // Clamp negatives to zero
        return Math.max(0.0, projection);
    }
}