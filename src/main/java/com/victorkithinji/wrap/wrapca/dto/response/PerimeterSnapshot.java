package com.victorkithinji.wrap.wrapca.dto.response;

import lombok.Value;

/**
 * One fire perimeter state at a point in time.
 * {@code perimeterGeoJson} is a GeoJSON FeatureCollection string.
 * {@code isoTimestamp} is the ISO-8601 wall-clock time of the simulation step.
 */
@Value
public class PerimeterSnapshot {
	String perimeterGeoJson;
	String isoTimestamp;
}