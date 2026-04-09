package com.victorkithinji.wrap.wrapca.grid;

import com.victorkithinji.wrap.wrapca.ingestion.EsaBandLayout;
import com.victorkithinji.wrap.wrapca.ingestion.GridBands;
import com.victorkithinji.wrap.wrapca.ingestion.RoadLayer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Constructs the CA grid from resampled environmental data.
 *
 * <p>Expects all three inputs already at CA target resolution — resampling
 * is the caller's responsibility (see {@code RasterResamplerService}).
 *
 * <p>Sequence on each {@link #build} call:
 * <ol>
 *   <li>Resolve {@link VegetationTypeEnum} per cell from ESA class codes via
 *       {@code EsaBandLayout.toVegetationType()} — no NDVI thresholding.</li>
 *   <li>Assemble {@link CellEnvironment} from CV bands (slope converted to
 *       radians; aspect already in radians from CV).</li>
 *   <li>Assign {@link CellStateEnum#NON_COMBUSTIBLE} to cells whose ESA code
 *       is BUILT (50), WATER (80, 90), or outside-deployment-area (70, 95).
 *       All other cells are {@link CellStateEnum#UNBURNED}.</li>
 *   <li>Compute {@code roadProximityMetres[row][col]} — minimum Euclidean
 *       distance in metres from each cell centre to the nearest road segment
 *       point in {@link RoadLayer}.</li>
 * </ol>
 *
 * <p>This service is stateless. Call {@code build()} again after a CV
 * fuel-state refresh to obtain a fresh grid.
 *
 * <p><strong>Handoff to Group 7:</strong> {@link GridInitResult#getRoadProximityMetres()}
 * replaces the OSM land-use proximity source previously planned by
 * {@code IgnitionLikelihoodIndexBuilder}. A value of {@link Float#MAX_VALUE}
 * means no roads were loaded and the road term in I(c) should be zeroed out.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GridInitialiserService {

    /** ESA codes that force NON_COMBUSTIBLE regardless of resolved VegetationType. */
    private static final java.util.Set<Integer> NON_COMBUSTIBLE_CODES = java.util.Set.of(
            EsaBandLayout.CODE_BUILT_UP,
            EsaBandLayout.CODE_PERMANENT_WATER,
            EsaBandLayout.CODE_HERBACEOUS_WETLAND,
            EsaBandLayout.CODE_SNOW_ICE,
            EsaBandLayout.CODE_MANGROVES
    );

    /**
     * NDMI scaling coefficients — map raw Sentinel-2 NDMI (approx −1 to +1)
     * to a Rothermel-compatible live fuel moisture fraction (approx 0.03–0.40).
     *
     * <p>Linear mapping: {@code moisture = MOISTURE_INTERCEPT + (ndmi − NDMI_DRY_ANCHOR) * MOISTURE_SLOPE}
     * Landmarks under these defaults:
     * <ul>
     *   <li>NDMI −0.1 → 0.03  (critically dry, maximum ROS)</li>
     *   <li>NDMI  0.3 → 0.14  (approaching GRASSLAND extinction at 0.15)</li>
     *   <li>NDMI  0.4 → 0.175 (above GRASSLAND extinction — grass will not spread)</li>
     * </ul>
     *
     * <p><strong>These are conservative estimates for East African savannah, not
     * field-calibrated values.</strong> Update the three constants below when
     * field moisture measurements become available. No other class needs to change.
     * Record any coefficient change in {@code deviation-discourse.md}.
     */
    private static final float NDMI_DRY_ANCHOR   = -0.1f;   // raw NDMI that maps to MOISTURE_MIN
    private static final float MOISTURE_MIN       =  0.03f;  // minimum physical moisture fraction (critically dry)
    private static final float MOISTURE_SLOPE     =  0.35f;  // (delta moisture) / (delta NDMI)
    private static final float MOISTURE_MAX       =  0.40f;  // upper clamp — saturated vegetation

    /**
     * Builds a {@link GridInitResult} from resampled inputs.
     *
     * @param bands        CV bands at CA target resolution.
     * @param esaCodes     ESA class codes at CA target resolution, sized
     *                     {@code [bands.getRows()][bands.getCols()]}.
     * @param roadLayer    Road linestring geometry in UTM 37S metres. May be
     *                     empty — handled gracefully.
     * @return A {@link GridInitResult} containing the initialised grid and
     *         the road proximity array.
     */
    public GridInitResult build(GridBands bands, int[][] esaCodes, RoadLayer roadLayer) {

        int rows = bands.getRows();
        int cols = bands.getCols();

        if (esaCodes.length != rows || esaCodes[0].length != cols) {
            throw new IllegalArgumentException(
                    ("GridBands and ESA codes are not aligned: " +
                            "GridBands is %dx%d but ESA codes are %dx%d. " +
                            "Both must be resampled to the same target resolution before calling build().")
                            .formatted(rows, cols, esaCodes.length, esaCodes[0].length));
        }

        log.info("Initialising CA grid: {}r x {}c  ({} cells, cellSize={}m)",
                rows, cols, (long) rows * cols, bands.getCellSizeMetres());

        int[][]             states            = new int[rows][cols];
        CellEnvironment[][] envs              = new CellEnvironment[rows][cols];
        float[][]           roadProximity     = new float[rows][cols];

        double cellSize  = bands.getCellSizeMetres();
        double originX   = bands.getMinX();
        double originY   = bands.getMaxY(); // row 0 is northernmost → maxY

        List<double[][]> segments = roadLayer.getSegments();
        int nonCombustibleCount   = 0;

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {

                // Cell centre in UTM 37S metres
                double cellCentreX = originX + (c + 0.5) * cellSize;
                double cellCentreY = originY - (r + 0.5) * cellSize;

                float ndvi         = bands.getNdvi()[r][c];
                float ndmi         = scaledMoisture(bands.getNdmi()[r][c]);
                float elevationM   = bands.getElevationMetres()[r][c];
                float slopeRad     = (float) Math.toRadians(bands.getSlopeDegrees()[r][c]);
                float aspectRad    = bands.getAspectRadians()[r][c];

                int                esaCode = esaCodes[r][c];
                VegetationTypeEnum vegType = EsaBandLayout.toVegetationTypeEnum(esaCode);

                envs[r][c] = new CellEnvironment(ndvi, ndmi, elevationM, slopeRad, aspectRad, vegType);

                if (NON_COMBUSTIBLE_CODES.contains(esaCode)) {
                    states[r][c] = CellStateEnum.NON_COMBUSTIBLE.ordinal();
                    nonCombustibleCount++;
                } else {
                    states[r][c] = CellStateEnum.UNBURNED.ordinal();
                }

                roadProximity[r][c] = minDistanceToRoads(cellCentreX, cellCentreY, segments);
            }
        }

        log.info("Grid ready — {} NON_COMBUSTIBLE, {} combustible",
                nonCombustibleCount, (long) rows * cols - nonCombustibleCount);

        CaGrid grid = new CaGrid(states, envs, rows, cols, cellSize);
        return new GridInitResult(grid, roadProximity);
    }

    // -------------------------------------------------------------------------
    // Band scaling
    // -------------------------------------------------------------------------

    /**
     * Maps raw Sentinel-2 NDMI (approx −1 to +1) to a Rothermel-compatible
     * live fuel moisture fraction consumed by {@code RothermelRosCalculator}.
     *
     * <p>The Rothermel moisture damping term compares this value directly against
     * {@code moistureOfExtinction} (e.g. 0.15 for GRASSLAND). Storing raw NDMI
     * without scaling would place most vegetated cells above extinction, producing
     * zero ROS across the entire grid.
     *
     * <p>Coefficients are defined as named constants above this method.
     * Update those constants — not this method — when field data is available.
     */
    private static float scaledMoisture(float rawNdmi) {
        float ndmi     = Math.max(-1.0f, Math.min(1.0f, rawNdmi));
        float moisture = MOISTURE_MIN + (ndmi - NDMI_DRY_ANCHOR) * MOISTURE_SLOPE;
        return Math.max(MOISTURE_MIN, Math.min(MOISTURE_MAX, moisture));
    }

    // -------------------------------------------------------------------------
    // Road proximity
    // -------------------------------------------------------------------------

    /**
     * Returns the minimum Euclidean distance in metres from ({@code px, py})
     * to the nearest point across all road segments.
     *
     * <p>Iterates over every vertex of every segment — sufficient for the
     * road densities expected in the Aberdare grid area without the complexity
     * of a spatial index.
     *
     * @return {@link Float#MAX_VALUE} when {@code segments} is empty.
     */
    private float minDistanceToRoads(double px, double py, List<double[][]> segments) {
        if (segments.isEmpty()) return Float.MAX_VALUE;

        double minSq = Double.MAX_VALUE;

        for (double[][] segment : segments) {
            for (double[] point : segment) {
                double dx = px - point[0];
                double dy = py - point[1];
                double distSq = dx * dx + dy * dy;
                if (distSq < minSq) minSq = distSq;
            }
        }

        return (float) Math.sqrt(minSq);
    }
}