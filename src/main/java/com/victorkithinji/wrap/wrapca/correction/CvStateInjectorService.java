package com.victorkithinji.wrap.wrapca.correction;

import com.victorkithinji.wrap.wrapca.cvintegration.FirePerimeterData;
import com.victorkithinji.wrap.wrapca.grid.CaGrid;
import com.victorkithinji.wrap.wrapca.grid.CellEnvironment;
import com.victorkithinji.wrap.wrapca.grid.CellStateEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Applies a CV observation layer to a running CaGrid at each satellite overpass.
 * <br/><br/>
 * Three hard state overrides are applied in order:
 *   1. Confirmed BURNED cells are forced to BURNED regardless of simulation state.
 *   2. Suppressed zone cells are registered in SuppressedZoneRegistry and their
 *      grid state is forced to NON_COMBUSTIBLE for the suppression window duration.
 *   3. NDMI values are refreshed for UNBURNED cells from the latest Sentinel-2 observation.
 *<br/><br/>
 * These are authoritative overrides, not probabilistic adjustments. The CA resumes
 * propagation from the corrected state after this service returns.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CvStateInjectorService {

    /**
     * Duration a suppressed zone cell remains NON_COMBUSTIBLE after detection.
     * One satellite overpass interval (approximately 12 hours for VIIRS polar orbit)
     * is a safe default — the next overpass will re-confirm or lift suppression.
     */
    private static final Duration SUPPRESSION_WINDOW = Duration.ofHours(12);

    private final SuppressedZoneRegistry suppressedZoneRegistry;

    /**
     * Applies all three correction types from the given FirePerimeterData to the grid.
     *
     * @param grid  the live CaGrid being corrected in place
     * @param data  the CV observation layer from the latest overpass
     */
    public void inject(CaGrid grid, FirePerimeterData data) {
        int applied = 0;

        applied += forceConfirmedBurned(grid, data);
        applied += registerSuppressedZones(grid, data);
        applied += refreshMoistureValues(grid, data);

        log.info("CV correction applied at {}: {} cell updates across 3 correction types",
                data.getObservationTime(), applied);
    }

    // --- private helpers ---

    private int forceConfirmedBurned(CaGrid grid, FirePerimeterData data) {
        int count = 0;
        int burnedOrdinal = CellStateEnum.BURNED.ordinal();

        for (long cellIndex : data.getConfirmedBurnedCellIndices()) {
            int row = (int) (cellIndex / grid.cols);
            int col = (int) (cellIndex % grid.cols);

            if (!grid.inBounds(row, col)) {
                log.warn("CV correction: confirmed-burned cell index {} is out of bounds, skipping", cellIndex);
                continue;
            }

            if (grid.states[row][col] != burnedOrdinal) {
                grid.states[row][col] = burnedOrdinal;
                count++;
            }
        }

        log.debug("forceConfirmedBurned: {} cells updated", count);
        return count;
    }

    private int registerSuppressedZones(CaGrid grid, FirePerimeterData data) {
        Instant expiresAt = Instant.now().plus(SUPPRESSION_WINDOW);
        int nonCombustibleOrdinal = CellStateEnum.NON_COMBUSTIBLE.ordinal();
        int count = 0;

        for (long cellIndex : data.getSuppressedZoneCellIndices()) {
            int row = (int) (cellIndex / grid.cols);
            int col = (int) (cellIndex % grid.cols);

            if (!grid.inBounds(row, col)) {
                log.warn("CV correction: suppressed zone cell index {} is out of bounds, skipping", cellIndex);
                continue;
            }

            suppressedZoneRegistry.register(cellIndex, expiresAt);
            grid.states[row][col] = nonCombustibleOrdinal;
            count++;
        }

        log.debug("registerSuppressedZones: {} cells suppressed until {}", count, expiresAt);
        return count;
    }

    private int refreshMoistureValues(CaGrid grid, FirePerimeterData data) {
        Map<Long, Float> updates = data.getUpdatedMoistureValues();
        if (updates == null || updates.isEmpty()) {
            return 0;
        }

        int unburnedOrdinal = CellStateEnum.UNBURNED.ordinal();
        int count = 0;

        for (Map.Entry<Long, Float> entry : updates.entrySet()) {
            long cellIndex = entry.getKey();
            float newNdmi = entry.getValue();

            int row = (int) (cellIndex / grid.cols);
            int col = (int) (cellIndex % grid.cols);

            if (!grid.inBounds(row, col)) {
                log.warn("CV correction: moisture update cell index {} is out of bounds, skipping", cellIndex);
                continue;
            }

            // Only refresh UNBURNED cells — no point updating fuel state for cells already consumed
            if (grid.states[row][col] != unburnedOrdinal) {
                continue;
            }

            CellEnvironment existing = grid.environment[row][col];
            grid.environment[row][col] = new CellEnvironment(
                    existing.getNdvi(),
                    newNdmi,
                    existing.getSlopeRadians(),
                    existing.getAspectRadians(),
                    existing.getVegetationType()
            );
            count++;
        }

        log.debug("refreshMoistureValues: {} UNBURNED cells updated", count);
        return count;
    }
}