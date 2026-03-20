package com.victorkithinji.wrap.wrapca.ingestion;

import com.victorkithinji.wrap.wrapca.config.SimulationConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Resamples raster data from native resolution (10 m) to the CA engine's
 * target cell size (default 100 m, configurable via
 * {@code wrap.simulation.cell-size-metres}).
 *
 * <p>Two resampling paths:
 * <ul>
 *   <li>{@link #resample(GridBands)} — continuous bands (NDVI, NDMI, elevation,
 *       slope, aspect). Uses block-averaging (mean). Nearest-neighbour is
 *       explicitly ruled out by the Group 4 contract.</li>
 *   <li>{@link #resampleEsa(EsaBands)} — categorical ESA class codes.
 *       Uses majority-class resampling. Tie-breaking rule: prefer combustible
 *       ESA class over non-combustible (conservative for fire risk).</li>
 * </ul>
 *
 * <p>Bounding box ({@code minX / minY / maxX / maxY}) is preserved unchanged
 * through both paths — resampling does not alter spatial extent.
 *
 * <p>If the native pixel size already equals the target cell size, each method
 * returns its input unchanged.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RasterResamplerService {

    private final SimulationConfig simulationConfig;

    /**
     * ESA class codes whose land cover is combustible.
     * Used for tie-breaking in {@link #resampleEsa(EsaBands)}.
     * Mirrors EsaBandLayout combustible mappings:
     *   10 = tree cover, 20 = shrubland, 30 = grassland, 40 = cropland.
     */
    private static final Set<Integer> COMBUSTIBLE_CODES = Set.of(
            EsaBandLayout.CODE_TREE_COVER,
            EsaBandLayout.CODE_SHRUBLAND,
            EsaBandLayout.CODE_GRASSLAND,
            EsaBandLayout.CODE_CROPLAND
    );

    // -------------------------------------------------------------------------
    // Continuous bands — block-averaging
    // -------------------------------------------------------------------------

    /**
     * Returns a {@link GridBands} at the target CA resolution.
     *
     * @param native_ GridBands from GeoTiffBandReaderService at native pixel size.
     * @return A new GridBands whose {@code rows}, {@code cols}, and
     *         {@code cellSizeMetres} reflect the target resolution.
     *         Returns the same object if no resampling is needed.
     */
    public GridBands resample(GridBands native_) {

        double targetCellSize = simulationConfig.getCellSizeMetres();
        double nativeCellSize = native_.getCellSizeMetres();

        if (Double.compare(nativeCellSize, targetCellSize) == 0) {
            log.info("GridBands: native cell size equals target ({}m) — skipping resample.", targetCellSize);
            return native_;
        }

        double scaleFactor = targetCellSize / nativeCellSize;
        guardAgainstUpsampling(scaleFactor, targetCellSize, nativeCellSize);

        int srcRows = native_.getRows();
        int srcCols = native_.getCols();
        int dstRows = (int) Math.ceil(srcRows / scaleFactor);
        int dstCols = (int) Math.ceil(srcCols / scaleFactor);

        log.info("GridBands: resampling {}x{} at {}m → {}x{} at {}m",
                srcRows, srcCols, nativeCellSize, dstRows, dstCols, targetCellSize);

        float[][] ndvi      = new float[dstRows][dstCols];
        float[][] ndmi      = new float[dstRows][dstCols];
        float[][] elevation = new float[dstRows][dstCols];
        float[][] slope     = new float[dstRows][dstCols];
        float[][] aspect    = new float[dstRows][dstCols];

        for (int dr = 0; dr < dstRows; dr++) {
            for (int dc = 0; dc < dstCols; dc++) {
                int r0 = (int) Math.floor(dr * scaleFactor);
                int c0 = (int) Math.floor(dc * scaleFactor);
                int r1 = Math.min((int) Math.ceil((dr + 1) * scaleFactor), srcRows);
                int c1 = Math.min((int) Math.ceil((dc + 1) * scaleFactor), srcCols);

                ndvi[dr][dc]      = blockMean(native_.getNdvi(),            r0, c0, r1, c1);
                ndmi[dr][dc]      = blockMean(native_.getNdmi(),            r0, c0, r1, c1);
                elevation[dr][dc] = blockMean(native_.getElevationMetres(), r0, c0, r1, c1);
                slope[dr][dc]     = blockMean(native_.getSlopeDegrees(),    r0, c0, r1, c1);
                aspect[dr][dc]    = blockMean(native_.getAspectRadians(),   r0, c0, r1, c1);
            }
        }

        return new GridBands(
                ndvi, ndmi, elevation, slope, aspect,
                dstRows, dstCols, targetCellSize,
                native_.getMinX(), native_.getMinY(),
                native_.getMaxX(), native_.getMaxY()
        );
    }

    // -------------------------------------------------------------------------
    // Categorical ESA bands — majority-class resampling
    // -------------------------------------------------------------------------

    /**
     * Resamples the ESA WorldCover class code raster to CA grid resolution.
     *
     * <p>Method: majority class — the most frequent code within each target
     * cell's footprint is selected. Tie-breaking rule: if two codes appear
     * equally often, prefer the combustible one (conservative for fire risk).
     * If all tied codes have equal combustibility, the lower numeric code wins
     * (deterministic, no further significance).
     *
     * @param native_ EsaBands from GeoTiffBandReaderService at native pixel size.
     * @return int[][] of ESA class codes at CA grid resolution,
     *         sized [dstRows][dstCols].
     *         Returns {@code native_.getClassCode()} unchanged if no resampling
     *         is needed.
     */
    public int[][] resampleEsa(EsaBands native_) {

        double targetCellSize = simulationConfig.getCellSizeMetres();
        double nativeCellSize = native_.getCellSizeMetres();

        if (Double.compare(nativeCellSize, targetCellSize) == 0) {
            log.info("EsaBands: native cell size equals target ({}m) — skipping resample.", targetCellSize);
            return native_.getClassCode();
        }

        double scaleFactor = targetCellSize / nativeCellSize;
        guardAgainstUpsampling(scaleFactor, targetCellSize, nativeCellSize);

        int srcRows   = native_.getRows();
        int srcCols   = native_.getCols();
        int dstRows   = (int) Math.ceil(srcRows / scaleFactor);
        int dstCols   = (int) Math.ceil(srcCols / scaleFactor);

        log.info("EsaBands: resampling {}x{} at {}m → {}x{} at {}m (majority class)",
                srcRows, srcCols, nativeCellSize, dstRows, dstCols, targetCellSize);

        int[][] result = new int[dstRows][dstCols];

        for (int dr = 0; dr < dstRows; dr++) {
            for (int dc = 0; dc < dstCols; dc++) {
                int r0 = (int) Math.floor(dr * scaleFactor);
                int c0 = (int) Math.floor(dc * scaleFactor);
                int r1 = Math.min((int) Math.ceil((dr + 1) * scaleFactor), srcRows);
                int c1 = Math.min((int) Math.ceil((dc + 1) * scaleFactor), srcCols);

                result[dr][dc] = majorityCode(native_.getClassCode(), r0, c0, r1, c1);
            }
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private float blockMean(float[][] src, int r0, int c0, int r1, int c1) {
        double sum   = 0.0;
        int    count = 0;
        for (int r = r0; r < r1; r++)
            for (int c = c0; c < c1; c++) {
                sum += src[r][c];
                count++;
            }
        return count == 0 ? 0f : (float) (sum / count);
    }

    /**
     * Selects the majority ESA class code within a block.
     *
     * <p>Tie-breaking: combustible codes beat non-combustible codes.
     * Among equally-scored codes, the lower numeric value wins (deterministic).
     */
    private int majorityCode(int[][] src, int r0, int c0, int r1, int c1) {
        Map<Integer, Integer> freq = new HashMap<>();
        for (int r = r0; r < r1; r++)
            for (int c = c0; c < c1; c++)
                freq.merge(src[r][c], 1, Integer::sum);

        int bestCode  = -1;
        int bestCount = -1;

        for (Map.Entry<Integer, Integer> entry : freq.entrySet()) {
            int code  = entry.getKey();
            int count = entry.getValue();

            if (count > bestCount) {
                bestCode  = code;
                bestCount = count;
            } else if (count == bestCount) {
                // Tie — prefer combustible; if same combustibility, prefer lower code
                boolean newCombustible  = COMBUSTIBLE_CODES.contains(code);
                boolean bestCombustible = COMBUSTIBLE_CODES.contains(bestCode);

                if (newCombustible && !bestCombustible) {
                    bestCode = code;
                } else if (newCombustible == bestCombustible && code < bestCode) {
                    bestCode = code;
                }
            }
        }

        return bestCode;
    }

    private void guardAgainstUpsampling(double scaleFactor, double target, double native_) {
        if (scaleFactor < 1.0) {
            throw new IllegalArgumentException(
                    ("Target cell size (%.1fm) is smaller than native pixel size (%.1fm). " +
                            "Up-sampling is not supported.")
                            .formatted(target, native_));
        }
    }
}