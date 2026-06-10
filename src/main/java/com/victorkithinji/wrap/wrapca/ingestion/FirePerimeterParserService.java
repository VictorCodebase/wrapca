package com.victorkithinji.wrap.wrapca.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

/**
 * Parses a CV-provided fire perimeter into a set of encoded CA grid cell indices.
 * <p>
 * Cell index encoding: index = row * gridCols + col
 * This matches the encoding used everywhere else in the system.
 * <p>
 * Input format: GeoJSON Feature or FeatureCollection containing a Polygon geometry
 * whose coordinates are in the CA grid's CRS (UTM 37S, metres).
 * <p>
 * All cells whose centre point falls inside the polygon are marked as BURNING.
 * Uses a ray-casting point-in-polygon test — sufficient for convex and mildly
 * concave fire perimeters. Highly concave perimeters (e.g. fire in a valley with
 * fingers) are handled correctly by ray-casting but worth noting as an assumption.
 * <p>
 * ─────────────────────────────────────────────────────────────────────────────
 * Coordinate system assumption:
 * The GeoJSON coordinates from CV are assumed to be in UTM 37S metres,
 * matching the CA grid's spatial reference. If CV provides WGS84 lat/lon,
 * the coordinates must be projected before calling this parser.
 * This needs to be confirmed with CV — it is the key contract point for
 * this service.
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Slf4j
@Service
public class FirePerimeterParserService {

	private final ObjectMapper objectMapper;

	public FirePerimeterParserService(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	/**
	 * Parses a GeoJSON polygon string into a set of encoded cell indices.
	 *
	 * @param perimeterGeoJson GeoJSON string (Feature or Polygon geometry)
	 * @param gridOriginX      easting of the top-left cell centre (metres, UTM 37S)
	 * @param gridOriginY      northing of the top-left cell centre (metres, UTM 37S)
	 * @param cellSizeMetres   cell size in metres
	 * @param gridRows         total rows in the CA grid
	 * @param gridCols         total columns in the CA grid
	 * @return set of encoded cell indices (row * gridCols + col) for cells inside the perimeter
	 */
	public Set<Long> parse(
		String perimeterGeoJson,
		double gridOriginX,
		double gridOriginY,
		double cellSizeMetres,
		int gridRows,
		int gridCols) throws IOException {


		JsonNode root = objectMapper.readTree(perimeterGeoJson);
		JsonNode geometry = resolveGeometry(root);
		List<double[][]> rings = extractExteriorRings(geometry);

		Set<Long> result = new HashSet<>();
		int contained = 0;

		// Handle out of boundary box errors
		// Compute bounding box of all rings
		double polyMinX = Double.POSITIVE_INFINITY, polyMaxX = Double.NEGATIVE_INFINITY;
		double polyMinY = Double.POSITIVE_INFINITY, polyMaxY = Double.NEGATIVE_INFINITY;
		for (double[][] ring : rings) {
			for (double[] pt : ring) {
				polyMinX = Math.min(polyMinX, pt[0]);
				polyMaxX = Math.max(polyMaxX, pt[0]);
				polyMinY = Math.min(polyMinY, pt[1]);
				polyMaxY = Math.max(polyMaxY, pt[1]);
			}
		}

		// Grid bounds
		double gridMaxX = gridOriginX + gridCols * cellSizeMetres;
		double gridMinY = gridOriginY - gridRows * cellSizeMetres; // because row 0 = north

		if (polyMaxX < gridOriginX || polyMinX > gridMaxX ||
			polyMaxY < gridMinY || polyMinY > gridOriginY) {
			log.error("Polygon bbox [{}, {}, {}, {}] does not overlap grid bbox [{}, {}, {}, {}]",
				polyMinX, polyMinY, polyMaxX, polyMaxY,
				gridOriginX, gridMinY, gridMaxX, gridOriginY);
			return Collections.emptySet();
		}

		for (int row = 0; row < gridRows; row++) {
			for (int col = 0; col < gridCols; col++) {

				double cellCentreX = gridOriginX + col * cellSizeMetres;
				double cellCentreY = gridOriginY - row * cellSizeMetres;

				boolean inside = false;

				for (double[][] ring : rings) {
					if (pointInPolygon(cellCentreX, cellCentreY, ring)) {
						inside = true;
						break;
					}
				}

				if (inside) {
					result.add((long) row * gridCols + col);
					contained++;
				}
			}
		}

		log.info("Parsed fire perimeter: {} cells marked as initial BURNING state", contained);
		return result;
	}

	private JsonNode resolveGeometry(JsonNode root) {
		String type = root.path("type").asText();
		return switch (type) {
			case "Feature" -> root.path("geometry");
			case "FeatureCollection" -> {
				JsonNode geometry = root.path("features").get(0).path("geometry");
				yield geometry;
			}
			case "Polygon", "MultiPolygon" -> root;
			default -> throw new IllegalArgumentException(
				"Unsupported GeoJSON type: " + type +
					". Expected Feature, FeatureCollection, Polygon, or MultiPolygon");
		};
	}

	private List<double[][]> extractExteriorRings(JsonNode geometry) {
		//JsonNode coordinates = geometry.path("coordinates").get(0); // exterior ring only
		List<double[][]> rings = new ArrayList<>();

		String type = geometry.path("type").asText();

		if ("Polygon".equals(type)) {

			rings.add(toRing(
				geometry.path("coordinates").get(0)
			));

		} else if ("MultiPolygon".equals(type)) {

			JsonNode polygons = geometry.path("coordinates");

			for (JsonNode polygon : polygons) {

				// polygon.get(0) = exterior ring
				rings.add(toRing(
					polygon.get(0)
				));
			}

		} else {

			throw new IllegalArgumentException(
				"Unsupported geometry type: " + type
			);
		}

		return rings;
	}

	private double[][] toRing(JsonNode coordinates) {

		double[][] ring = new double[coordinates.size()][2];

		for (int i = 0; i < coordinates.size(); i++) {

			ring[i][0] = coordinates.get(i).get(0).asDouble();
			ring[i][1] = coordinates.get(i).get(1).asDouble();
		}

		return ring;
	}

	/**
	 * Ray-casting point-in-polygon test.
	 * Returns true if (px, py) is inside the polygon defined by ring.
	 */
	private boolean pointInPolygon(double px, double py, double[][] ring) {
		boolean inside = false;
		int n = ring.length;
		for (int i = 0, j = n - 1; i < n; j = i++) {
			double xi = ring[i][0], yi = ring[i][1];
			double xj = ring[j][0], yj = ring[j][1];
			boolean intersects = ((yi > py) != (yj > py))
				&& (px < (xj - xi) * (py - yi) / (yj - yi) + xi);
			if (intersects) inside = !inside;
		}
		return inside;
	}
}