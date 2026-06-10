package com.victorkithinji.wrap.wrapca.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads OSM road and path linestring geometry for the grid area.
 *
 * Reads from a pre-downloaded GeoJSON file. The file is downloaded once and
 * cached — this service does not call the Overpass API at runtime. If the
 * file is missing, an empty RoadLayer is returned and the simulation proceeds
 * with zero road proximity influence on I(c).
 *
 * OSM scope (DEV-002): limited to highway tags track, path, unclassified,
 * and tertiary. These are the access routes relevant to forest ignition risk
 * in the Aberdare deployment area. Built-up masking is handled by ESA
 * WorldCover class code 50 — OSM is no longer responsible for that.
 *
 * Coordinate convention: all coordinates in the output RoadLayer are in
 * UTM 37S metres (EPSG:32737), matching the CA grid's spatial reference.
 * The input GeoJSON is expected to already be in UTM 37S. If the file was
 * downloaded from Overpass in WGS84, it must be reprojected before caching —
 * this service does not reproject.
 */
@Slf4j
@Service
public class OsmRoadLoaderService {

    private static final String[] ACCEPTED_HIGHWAY_TAGS =
            {"track", "path", "unclassified", "tertiary"};

    private final Path roadsPath;
    private final ObjectMapper objectMapper;

    public OsmRoadLoaderService(
            @Value("${wrap.data.roads-path}") String roadsPath,
            ObjectMapper objectMapper) {
        this.roadsPath    = Paths.get(roadsPath);
        this.objectMapper = objectMapper;
    }

    /**
     * Loads road linestring geometry from the configured GeoJSON file.
     *
     * @return RoadLayer containing linestring segments in UTM 37S metres,
     *         or an empty RoadLayer if the file is missing or unreadable
     */
    public RoadLayer load() {
        if (!Files.exists(roadsPath)) {
            log.warn("OSM road file not found at {}. Road proximity will not " +
                            "contribute to I(c) — all cells will have maximum road distance.",
                    roadsPath);
            return new RoadLayer(List.of());
        }

        try {
            String geojson = Files.readString(roadsPath);
            List<double[][]> segments = parseLinestrings(geojson);
            log.info("Loaded {} road/path segments from {}", segments.size(), roadsPath);
            return new RoadLayer(segments);
        } catch (IOException e) {
            log.warn("Failed to read OSM road file at {}: {}. " +
                            "Road proximity will not contribute to I(c).",
                    roadsPath, e.getMessage());
            return new RoadLayer(List.of());
        }
    }

    private List<double[][]> parseLinestrings(String geojson) throws IOException {
        JsonNode root       = objectMapper.readTree(geojson);
        JsonNode features   = resolveFeatureCollection(root);
        List<double[][]> segments = new ArrayList<>();

        for (JsonNode feature : features) {
            if (!isAcceptedHighway(feature)) continue;

            JsonNode geometry = feature.path("geometry");
            String geomType   = geometry.path("type").asText();

            if ("LineString".equals(geomType)) {
                double[][] coords = parseCoordinateArray(geometry.path("coordinates"));
                if (coords.length >= 2) segments.add(coords);

            } else if ("MultiLineString".equals(geomType)) {
                for (JsonNode line : geometry.path("coordinates")) {
                    double[][] coords = parseCoordinateArray(line);
                    if (coords.length >= 2) segments.add(coords);
                }
            }
            // Points and polygons in OSM road data are ignored
        }
        return segments;
    }

    private JsonNode resolveFeatureCollection(JsonNode root) {
        String type = root.path("type").asText();
        if ("FeatureCollection".equals(type)) return root.path("features");
        if ("Feature".equals(type))           return objectMapper.createArrayNode().add(root);
        throw new IllegalArgumentException(
                "Unsupported GeoJSON root type for road layer: " + type);
    }

    private boolean isAcceptedHighway(JsonNode feature) {
        String highway = feature.path("properties").path("highway").asText("");
        for (String tag : ACCEPTED_HIGHWAY_TAGS) {
            if (tag.equals(highway)) return true;
        }
        return false;
    }

    private double[][] parseCoordinateArray(JsonNode coordinatesNode) {
        double[][] result = new double[coordinatesNode.size()][2];
        for (int i = 0; i < coordinatesNode.size(); i++) {
            result[i][0] = coordinatesNode.get(i).get(0).asDouble(); // X (easting)
            result[i][1] = coordinatesNode.get(i).get(1).asDouble(); // Y (northing)
        }
        return result;
    }
}