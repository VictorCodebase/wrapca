package com.victorkithinji.wrap.wrapca.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Loads ERA5 wind data and interpolates it to the CA grid resolution.
 *
 * Current implementation: reads from a local JSON stub file.
 * ERA5 native resolution is ~31 km. At 100m CA grid scale, we hold the
 * wind field spatially uniform (single value interpolated across all cells)
 * unless a per-cell ERA5 HRES product is available.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Wind stub file format (data/wind/era5_wind_stub.json):
 * {
 *   "speedMs": 4.2,
 *   "directionDeg": 225.0
 * }
 * A single speed and direction representing the domain-average wind.
 * This is sufficient for Phase 1 climatological runs and early Phase 2 testing.
 * Replace with a gridded source when ERA5 HRES data is integrated.
 *
 * Interpolation note:
 * ERA5's ~31km resolution means a single wind vector covers ~310 CA cells.
 * For the Aberdare deployment area (~200x200 cells = 20km x 20km), the entire
 * domain typically falls within one or two ERA5 grid points, making uniform
 * interpolation a reasonable simplification rather than a meaningful tradeoff.
 * The terrain-induced wind variation that ERA5 misses at this scale is partially
 * compensated by the slope effect term in RothermelRosCalculator.
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Slf4j
@Service
public class WindFieldLoaderService {

    private final Path windStubPath;
    private final ObjectMapper objectMapper;

    public WindFieldLoaderService(
            @Value("${wrap.data.root}") String dataRoot,
            ObjectMapper objectMapper) {
        this.windStubPath = Paths.get(dataRoot, "wind", "era5_wind_stub.json");
        this.objectMapper = objectMapper;
    }

    /**
     * Loads a uniform wind field sized to match the given grid dimensions.
     * All cells receive the same speed and direction from the stub file.
     *
     * @param rows grid row count (from GridBands or CaGrid)
     * @param cols grid column count
     * @return WindField with uniform values across all cells
     * @throws IOException if the stub file cannot be read
     */
    public WindField load(int rows, int cols) throws IOException {
        if (!Files.exists(windStubPath)) {
            log.warn("Wind stub not found at {}. Using calm conditions (0 m/s).", windStubPath);
            return buildUniform(0.0f, 0.0f, rows, cols);
        }

        try (InputStream is = Files.newInputStream(windStubPath)) {
            JsonNode root = objectMapper.readTree(is);
            float speedMs     = (float) root.get("speedMs").asDouble();
            float directionDeg = (float) root.get("directionDeg").asDouble();
            log.info("Loaded wind: {:.1f} m/s from {:.0f}°", speedMs, directionDeg);
            return buildUniform(speedMs, directionDeg, rows, cols);
        }
    }

    private WindField buildUniform(float speedMs, float directionDeg, int rows, int cols) {
        float[][] speed  = new float[rows][cols];
        float[][] direction = new float[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                speed[r][c]     = speedMs;
                direction[r][c] = directionDeg;
            }
        }
        return new WindField(speed, direction, rows, cols);
    }
}