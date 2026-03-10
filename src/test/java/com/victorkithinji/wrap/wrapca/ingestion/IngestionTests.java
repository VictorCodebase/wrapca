package com.victorkithinji.wrap.wrapca.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.victorkithinji.wrap.wrapca.util.SyntheticGeoTiffGenerator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for all Group 4 ingestion services.
 * Each test is self-contained and uses TempDir — no shared state.
 */
class IngestionTests {

    @Nested
    class IngestionCacheServiceTest {

        @Test
        void cacheMiss_returnsEmpty(@TempDir Path tempDir) {
            IngestionCacheService svc = new IngestionCacheService(tempDir.toString());
            assertThat(svc.getCachedFuelStateForDate(LocalDate.of(2026, 3, 9)))
                    .isEmpty();
        }

        @Test
        void storeAndRetrieve_returnsCachedPath(@TempDir Path tempDir) throws IOException {
            IngestionCacheService svc = new IngestionCacheService(tempDir.toString());
            byte[] content = "fake-tiff-bytes".getBytes();
            LocalDate date = LocalDate.of(2026, 3, 9);

            svc.storeFuelStateForDate(content, date);

            assertThat(svc.getCachedFuelStateForDate(date)).isPresent();
        }

        @Test
        void storedFileContainsCorrectBytes(@TempDir Path tempDir) throws IOException {
            IngestionCacheService svc = new IngestionCacheService(tempDir.toString());
            byte[] content = new byte[]{1, 2, 3, 4, 5};
            LocalDate date = LocalDate.of(2026, 3, 9);

            Path stored = svc.storeFuelStateForDate(content, date);

            assertThat(Files.readAllBytes(stored)).isEqualTo(content);
        }

        @Test
        void differentDates_doNotConflict(@TempDir Path tempDir) throws IOException {
            IngestionCacheService svc = new IngestionCacheService(tempDir.toString());
            svc.storeFuelStateForDate("day1".getBytes(), LocalDate.of(2026, 3, 9));

            assertThat(svc.getCachedFuelStateForDate(LocalDate.of(2026, 3, 10))).isEmpty();
        }
    }

    // ─── GeoTiffBandReaderService ─────────────────────────────────────────────

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GeoTiffBandReaderServiceTest {

        // The fixture is written to a fixed subdirectory of the system temp folder
        // rather than a JUnit @TempDir. This sidesteps a Windows-specific issue
        // where GeoTools / JAI holds a file handle via its internal tile cache
        // even after coverage.dispose(true) and reader.dispose(). JUnit's @TempDir
        // cleanup runs synchronously after the last test and fails if any handle
        // is still open. By managing the directory ourselves with a retry-delete
        // in @AfterAll, we give the JVM time to release the handle before
        // treating deletion failure as an error.
        private static Path fixtureDir;
        private static Path syntheticTiff;

        @BeforeAll
        void generateFixture() throws Exception {
            fixtureDir = Files.createTempDirectory("wrap_geotiff_test_");
            syntheticTiff = fixtureDir.resolve("test_fixture.tif");
            SyntheticGeoTiffGenerator.generate(syntheticTiff.toFile());
        }

        @AfterAll
        void deleteFixture() throws Exception {
            // JAI may still hold the file handle briefly after the last test.
            // Retry deletion up to 5 times with a short pause before giving up.
            for (int attempt = 1; attempt <= 5; attempt++) {
                try {
                    Files.deleteIfExists(syntheticTiff);
                    Files.deleteIfExists(fixtureDir);
                    return;
                } catch (IOException e) {
                    if (attempt == 5) {
                        System.err.println("Could not delete fixture after 5 attempts: "
                                + e.getMessage() + " — leftover in: " + fixtureDir);
                    } else {
                        Thread.sleep(200L * attempt);
                    }
                }
            }
        }

        @Test
        void readReturnsCorrectDimensions() throws IOException {
            GeoTiffBandReaderService svc = new GeoTiffBandReaderService(100.0);
            GridBands bands = svc.read(syntheticTiff);

            assertThat(bands.getRows()).isEqualTo(200);
            assertThat(bands.getCols()).isEqualTo(200);
        }

        @Test
        void ndviArrayHasExpectedForestValues() throws IOException {
            GeoTiffBandReaderService svc = new GeoTiffBandReaderService(100.0);
            GridBands bands = svc.read(syntheticTiff);

            // Centre of grid should be Afromontane forest (~0.65)
            float centrNdvi = bands.getNdvi()[100][100];
            assertThat(centrNdvi).isBetween(0.55f, 0.75f);
        }

        @Test
        void ndviArrayHasGrasslandPatch() throws IOException {
            GeoTiffBandReaderService svc = new GeoTiffBandReaderService(100.0);
            GridBands bands = svc.read(syntheticTiff);

            // SW grassland patch (rows 120-160, cols 20-60)
            float patchNdvi = bands.getNdvi()[140][40];
            assertThat(patchNdvi).isBetween(0.18f, 0.35f);
        }

        @Test
        void ndviArrayHasNearZeroWaterPatch() throws IOException {
            GeoTiffBandReaderService svc = new GeoTiffBandReaderService(100.0);
            GridBands bands = svc.read(syntheticTiff);

            // NE bare/water patch (rows 0-20, cols 180-200)
            float waterNdvi = bands.getNdvi()[5][190];
            assertThat(waterNdvi).isBetween(-0.05f, 0.1f);
        }

        @Test
        void elevationIncreasesWestToEast() throws IOException {
            GeoTiffBandReaderService svc = new GeoTiffBandReaderService(100.0);
            GridBands bands = svc.read(syntheticTiff);

            float westElev = bands.getElevationMetres()[100][0];
            float eastElev = bands.getElevationMetres()[100][199];
            assertThat(eastElev).isGreaterThan(westElev);
        }

        @Test
        void cellSizeReportedCorrectly() throws IOException {
            GeoTiffBandReaderService svc = new GeoTiffBandReaderService(100.0);
            GridBands bands = svc.read(syntheticTiff);

            assertThat(bands.getCellSizeMetres()).isCloseTo(100.0, within(1.0));
        }

        @Test
        void throwsOnMissingFile() {
            GeoTiffBandReaderService svc = new GeoTiffBandReaderService(100.0);
            assertThatThrownBy(() -> svc.read(Path.of("/nonexistent/path.tif")))
                    .isInstanceOf(IOException.class);
        }
    }

    // ─── WindFieldLoaderService ───────────────────────────────────────────────

    @Nested
    class WindFieldLoaderServiceTest {

        @Test
        void missingStubFile_returnsZeroWind(@TempDir Path tempDir) throws IOException {
            WindFieldLoaderService svc =
                    new WindFieldLoaderService(tempDir.toString(), new ObjectMapper());
            WindField field = svc.load(10, 10);

            assertThat(field.getSpeedMs()[0][0]).isEqualTo(0.0f);
            assertThat(field.getDirectionDeg()[0][0]).isEqualTo(0.0f);
        }

        @Test
        void stubFile_populatesAllCells(@TempDir Path tempDir) throws IOException {
            Path windDir = tempDir.resolve("wind");
            Files.createDirectories(windDir);
            Files.writeString(windDir.resolve("era5_wind_stub.json"),
                    "{\"speedMs\": 5.5, \"directionDeg\": 270.0}");

            WindFieldLoaderService svc =
                    new WindFieldLoaderService(tempDir.toString(), new ObjectMapper());
            WindField field = svc.load(3, 4);

            assertThat(field.getRows()).isEqualTo(3);
            assertThat(field.getCols()).isEqualTo(4);
            // All cells should have the same value (uniform field)
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 4; c++) {
                    assertThat(field.getSpeedMs()[r][c]).isCloseTo(5.5f, within(0.01f));
                    assertThat(field.getDirectionDeg()[r][c]).isCloseTo(270.0f, within(0.01f));
                }
            }
        }
    }

    // ─── FirePerimeterParserService ───────────────────────────────────────────

    @Nested
    class FirePerimeterParserServiceTest {

        private final FirePerimeterParserService svc =
                new FirePerimeterParserService(new ObjectMapper());

        // A 300m x 300m square centred at (500m, 500m) in a 10x10 grid with 100m cells.
        // Origin at (0, 900) → cell[0][0] centre at (50, 850), etc.
        private static final String SQUARE_POLYGON = """
                {
                  "type": "Polygon",
                  "coordinates": [[
                    [200.0, 200.0],
                    [800.0, 200.0],
                    [800.0, 800.0],
                    [200.0, 800.0],
                    [200.0, 200.0]
                  ]]
                }
                """;

        @Test
        void polygonCellsAreMarked() throws IOException {
            // Grid: 10x10, 100m cells, origin at (0, 900) in UTM metres
            Set<Long> cells = svc.parse(SQUARE_POLYGON, 50.0, 850.0, 100.0, 10, 10);
            assertThat(cells).isNotEmpty();
        }

        @Test
        void cellsOutsidePolygonAreExcluded() throws IOException {
            Set<Long> cells = svc.parse(SQUARE_POLYGON, 50.0, 850.0, 100.0, 10, 10);
            // Corner cells at (0,0) and (9,9) are outside the polygon
            assertThat(cells).doesNotContain(0L);           // row=0, col=0
            assertThat(cells).doesNotContain(9L * 10 + 9); // row=9, col=9
        }

        @Test
        void featureGeoJsonIsAccepted() throws IOException {
            String feature = """
                    {
                      "type": "Feature",
                      "geometry": %s,
                      "properties": {}
                    }
                    """.formatted(SQUARE_POLYGON);
            Set<Long> cells = svc.parse(feature, 50.0, 850.0, 100.0, 10, 10);
            assertThat(cells).isNotEmpty();
        }

        @Test
        void unsupportedGeoJsonType_throwsIllegalArgument() {
            String bad = "{\"type\": \"MultiPolygon\", \"coordinates\": []}";
            assertThatThrownBy(() -> svc.parse(bad, 0, 0, 100.0, 10, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unsupported GeoJSON type");
        }
    }
}