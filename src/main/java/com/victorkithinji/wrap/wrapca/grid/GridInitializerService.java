package com.victorkithinji.wrap.wrapca.grid;

import com.victorkithinji.wrap.wrapca.ingestion.BandData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Constructs the CaGrid from parsed band data produced by GeoTiffBandReaderService.
 *
 * Responsibilities:
 *  - Assign a CellEnvironment to every cell from NDVI, NDMI, slope, aspect, and
 *    vegetation-type bands.
 *  - Mark NON_COMBUSTIBLE cells for water, built, and bare-soil types so the engine
 *    never evaluates them.
 *  - Initialise all combustible cells to UNBURNED.
 *
 * This service is stateless: call build() as many times as needed (e.g. after a CV
 * correction refreshes the fuel state). Each call returns a fresh CaGrid.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GridInitializerService {

    /**
     * Builds a CaGrid from the supplied band data.
     *
     * @param data        Aligned float arrays for NDVI, NDMI, slope, aspect, and a
     *                    vegetation-type code per cell.
     * @param cellSizeMetres CA cell resolution (typically 100 m).
     * @return A fully initialised CaGrid ready for simulation.
     */
    public CaGrid build(BandData data, double cellSizeMetres) {

        int rows = data.rows();
        int cols = data.cols();

        log.info("Initialising CA grid: {}r x {}c  ({} cells, cellSize={}m)",
                rows, cols, (long) rows * cols, cellSizeMetres);

        int[][]            states      = new int[rows][cols];
        CellEnvironment[][] envs       = new CellEnvironment[rows][cols];

        int nonCombustibleCount = 0;

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {

                float ndvi   = data.ndvi()[r][c];
                float ndmi   = data.ndmi()[r][c];
                float slope  = data.slopeRadians()[r][c];
                float aspect = data.aspectRadians()[r][c];

                VegetationTypeEnum vegType = resolveVegetationType(data.vegetationCode()[r][c]);

                envs[r][c] = new CellEnvironment(ndvi, ndmi, slope, aspect, vegType);

                if (isNonCombustible(vegType)) {
                    states[r][c] = CellStateEnum.NON_COMBUSTIBLE.ordinal();
                    nonCombustibleCount++;
                } else {
                    states[r][c] = CellStateEnum.UNBURNED.ordinal();
                }
            }
        }

        log.info("Grid ready — {} NON_COMBUSTIBLE, {} combustible",
                nonCombustibleCount, (long) rows * cols - nonCombustibleCount);

        return new CaGrid(states, envs, rows, cols, cellSizeMetres);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Maps the integer vegetation code coming from the GeoTIFF band to the domain enum.
     * Codes mirror the order of VegetationTypeEnum ordinals (0 = AFROMONTANE_FOREST …).
     * An unrecognised code falls back to BARE_SOIL — non-ideal but safe.
     */
    private VegetationTypeEnum resolveVegetationType(int code) {
        VegetationTypeEnum[] values = VegetationTypeEnum.values();
        if (code >= 0 && code < values.length) {
            return values[code];
        }
        log.warn("Unknown vegetation code {} — defaulting to BARE_SOIL", code);
        return VegetationTypeEnum.BARE_SOIL;
    }

    /**
     * Returns true for vegetation types that must never ignite.
     * WATER and BUILT are structurally non-combustible.
     * BARE_SOIL has negligible fuel load and is treated as non-combustible to keep
     * the Rothermel computation well-defined (zero fuel load edge case avoided).
     */
    private boolean isNonCombustible(VegetationTypeEnum vegType) {
        return vegType == VegetationTypeEnum.WATER
                || vegType == VegetationTypeEnum.BUILT
                || vegType == VegetationTypeEnum.BARE_SOIL;
    }
}