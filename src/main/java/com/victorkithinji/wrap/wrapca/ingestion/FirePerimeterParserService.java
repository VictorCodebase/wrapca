package com.victorkithinji.wrap.wrapca.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Parses a CV-provided fire perimeter into a set of encoded CA grid cell indices.
 *
 * Cell index encoding: index = row * gridCols + col
 * This matches the encoding used everywhere else in the system.
 *
 * Input format: GeoJSON Feature or FeatureCollection containing a Polygon geometry
 * whose coordinates are in the CA grid's CRS (UTM 37S, metres).
 *
 * All cells whose centre point falls inside the polygon are marked as BURNING.
 * Uses a ray-casting point-in-polygon test — sufficient for convex and mildly
 * concave fire perimeters. Highly concave perimeters (e.g. fire in a valley with
 * fingers) are handled correctly by ray-casting but worth noting as an assumption.
 *
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
        double[][] ring = extractExteriorRing(geometry);

        Set<Long> result = new HashSet<>();
        int contained = 0;

        for (int row = 0; row < gridRows; row++) {
            for (int col = 0; col < gridCols; col++) {
                double cellCentreX = gridOriginX + col * cellSizeMetres;
                double cellCentreY = gridOriginY - row * cellSizeMetres; // Y decreases going down
                if (pointInPolygon(cellCentreX, cellCentreY, ring)) {
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
            case "Feature"           -> root.path("geometry");
            case "FeatureCollection" -> root.path("features").get(0).path("geometry");
            case "Polygon"           -> root;
            default -> throw new IllegalArgumentException(
                    "Unsupported GeoJSON type: " + type +
                            ". Expected Feature, FeatureCollection, or Polygon.");
        };
    }

    private double[][] extractExteriorRing(JsonNode geometry) {
        JsonNode coordinates = geometry.path("coordinates").get(0); // exterior ring only
        double[][] ring = new double[coordinates.size()][2];
        for (int i = 0; i < coordinates.size(); i++) {
            ring[i][0] = coordinates.get(i).get(0).asDouble(); // X (easting)
            ring[i][1] = coordinates.get(i).get(1).asDouble(); // Y (northing)
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