package com.victorkithinji.wrap.wrapca.cvintegration;

import lombok.Value;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Payload returned by the CV module's fire perimeter endpoint.
 * Carries all three correction types defined in proposal section 3.1.4.
 *
 * confirmedBurnedCellIndices:  cells confirmed BURNED by thermal detection.
 *     These are hard overrides — CvStateInjectorService forces them to BURNED
 *     regardless of current simulation state.
 *
 * suppressedZoneCellIndices:   cells where firefighting intervention was detected.
 *     These are temporarily set to NON_COMBUSTIBLE for the suppression window.
 *     SuppressedZoneRegistry manages their expiry.
 *
 * updatedMoistureValues:       fresh NDMI readings for UNBURNED cells.
 *     Keys are encoded cell indices (row * gridCols + col).
 *     Only cells with meaningfully changed moisture are included — CV should
 *     not send a full-grid update on every overpass.
 *
 * observationTime:             UTC instant of the VIIRS satellite overpass.
 *     Used by SuppressedZoneRegistry to calculate suppression expiry windows.
 */
@Value
public class FirePerimeterData {
    String perimeterGeoJson;
    List<Long> confirmedBurnedCellIndices;
    List<Long> suppressedZoneCellIndices;
    Map<Long, Float> updatedMoistureValues;
    Instant observationTime;
}