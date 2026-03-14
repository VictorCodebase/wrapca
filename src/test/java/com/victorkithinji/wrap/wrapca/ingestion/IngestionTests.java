package com.victorkithinji.wrap.wrapca.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.victorkithinji.wrap.wrapca.grid.VegetationTypeEnum;
import com.victorkithinji.wrap.wrapca.ingestion.*;
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

    // ─── IngestionCacheService ───────────────────────────────────────────────

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
        void readReturnsNativeResolutionDimensions() throws IOException {
            GeoTiffBandReaderService svc = new GeoTiffBandReaderService(100.0);
            GridBands bands = svc.read(syntheticTiff);

            // Native resolution is 10m — 20km area = 2000x2000 cells
            assertThat(bands.getRows()).isEqualTo(2000);
            assertThat(bands.getCols()).isEqualTo(2000);
        }

        @Test
        void nativePixelSizeIsReported() throws IOException {
            GeoTiffBandReaderService svc = new GeoTiffBandReaderService(100.0);
            GridBands bands = svc.read(syntheticTiff);

            assertThat(bands.getCellSizeMetres()).isCloseTo(10.0, within(0.5));
        }

        @Test
        void ndviForestValuesInCentre() throws IOException {
            GeoTiffBandReaderService svc = new GeoTiffBandReaderService(100.0);
            GridBands bands = svc.read(syntheticTiff);

            // Centre of grid: Afromontane forest (~0.65)
            float centreNdvi = bands.getNdvi()[1000][1000];
            assertThat(centreNdvi).isBetween(0.55f, 0.75f);
        }

        @Test
        void ndviGrasslandPatchPresent() throws IOException {
            GeoTiffBandReaderService svc = new GeoTiffBandReaderService(100.0);
            GridBands bands = svc.read(syntheticTiff);

            // SW grassland patch (rows 1200-1600, cols 200-600 at 10m)
            float patchNdvi = bands.getNdvi()[1400][400];
            assertThat(patchNdvi).isBetween(0.18f, 0.35f);
        }

        @Test
        void ndviWaterPatchPresent() throws IOException {
            GeoTiffBandReaderService svc = new GeoTiffBandReaderService(100.0);
            GridBands bands = svc.read(syntheticTiff);

            // NE water patch (rows 0-200, cols 1800-2000 at 10m)
            float waterNdvi = bands.getNdvi()[50][1900];
            assertThat(waterNdvi).isBetween(-0.05f, 0.1f);
        }

        @Test
        void elevationIncreasesWestToEast() throws IOException {
            GeoTiffBandReaderService svc = new GeoTiffBandReaderService(100.0);
            GridBands bands = svc.read(syntheticTiff);

            float westElev = bands.getElevationMetres()[1000][0];
            float eastElev = bands.getElevationMetres()[1000][1999];
            assertThat(eastElev).isGreaterThan(westElev);
        }

        @Test
        void slopeBandIsPresent() throws IOException {
            GeoTiffBandReaderService svc = new GeoTiffBandReaderService(100.0);
            GridBands bands = svc.read(syntheticTiff);

            // Slope values should be non-negative degrees
            float slope = bands.getSlopeDegrees()[1000][1000];
            assertThat(slope).isGreaterThanOrEqualTo(0f);
        }

        @Test
        void aspectBandIsPresent() throws IOException {
            GeoTiffBandReaderService svc = new GeoTiffBandReaderService(100.0);
            GridBands bands = svc.read(syntheticTiff);

            // Aspect is uniform westerly (~4.71 radians = 270 degrees)
            float aspect = bands.getAspectRadians()[1000][1000];
            assertThat(aspect).isCloseTo((float)(Math.PI * 1.5), within(0.1f));
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

    // ─── EsaBandLayout ────────────────────────────────────────────────────────

    @Nested
    class EsaBandLayoutTest {

        @Test
        void treeCoversResolvesToForest() {
            assertThat(EsaBandLayout.toVegetationTypeEnum(EsaBandLayout.CODE_TREE_COVER))
                    .isEqualTo(VegetationTypeEnum.AFROMONTANE_FOREST);
        }

        @Test
        void shrublandResolvesToShrubland() {
            assertThat(EsaBandLayout.toVegetationTypeEnum(EsaBandLayout.CODE_SHRUBLAND))
                    .isEqualTo(VegetationTypeEnum.SHRUBLAND);
        }

        @Test
        void grasslandResolvesToGrassland() {
            assertThat(EsaBandLayout.toVegetationTypeEnum(EsaBandLayout.CODE_GRASSLAND))
                    .isEqualTo(VegetationTypeEnum.GRASSLAND);
        }

        @Test
        void croplandResolvesToCropland() {
            assertThat(EsaBandLayout.toVegetationTypeEnum(EsaBandLayout.CODE_CROPLAND))
                    .isEqualTo(VegetationTypeEnum.CROPLAND);
        }

        @Test
        void builtUpResolvesToBuilt() {
            assertThat(EsaBandLayout.toVegetationTypeEnum(EsaBandLayout.CODE_BUILT_UP))
                    .isEqualTo(VegetationTypeEnum.BUILT);
        }

        @Test
        void permanentWaterResolvesToWater() {
            assertThat(EsaBandLayout.toVegetationTypeEnum(EsaBandLayout.CODE_PERMANENT_WATER))
                    .isEqualTo(VegetationTypeEnum.WATER);
        }

        @Test
        void herbaceousWetlandResolvesToWater() {
            assertThat(EsaBandLayout.toVegetationTypeEnum(EsaBandLayout.CODE_HERBACEOUS_WETLAND))
                    .isEqualTo(VegetationTypeEnum.WATER);
        }

        @Test
        void unrecognisedCodeFallsBackToBareSoil() {
            assertThat(EsaBandLayout.toVegetationTypeEnum(999))
                    .isEqualTo(VegetationTypeEnum.BARE_SOIL);
        }

        @Test
        void safeOutOfAreaCodesFallBackToBareSoil() {
            assertThat(EsaBandLayout.toVegetationTypeEnum(EsaBandLayout.CODE_SNOW_ICE))
                    .isEqualTo(VegetationTypeEnum.BARE_SOIL);
            assertThat(EsaBandLayout.toVegetationTypeEnum(EsaBandLayout.CODE_MANGROVES))
                    .isEqualTo(VegetationTypeEnum.BARE_SOIL);
            assertThat(EsaBandLayout.toVegetationTypeEnum(EsaBandLayout.CODE_MOSS_LICHEN))
                    .isEqualTo(VegetationTypeEnum.BARE_SOIL);
        }
    }

    // ─── IngestionCacheService (ESA and road additions) ───────────────────────

    @Nested
    class IngestionCacheServiceEsaRoadTest {

        @Test
        void esaCacheMiss_returnsEmpty(@TempDir Path tempDir) {
            IngestionCacheService svc = new IngestionCacheService(tempDir.toString());
            assertThat(svc.getCachedEsaLayer()).isEmpty();
        }

        @Test
        void storeAndRetrieveEsa(@TempDir Path tempDir) throws IOException {
            IngestionCacheService svc = new IngestionCacheService(tempDir.toString());
            svc.storeEsaLayer("fake-esa-tiff".getBytes());
            assertThat(svc.getCachedEsaLayer()).isPresent();
        }

        @Test
        void roadCacheMiss_returnsEmpty(@TempDir Path tempDir) {
            IngestionCacheService svc = new IngestionCacheService(tempDir.toString());
            assertThat(svc.getCachedRoadLayer()).isEmpty();
        }

        @Test
        void storeAndRetrieveRoads(@TempDir Path tempDir) throws IOException {
            IngestionCacheService svc = new IngestionCacheService(tempDir.toString());
            svc.storeRoadLayer("{\"type\":\"FeatureCollection\",\"features\":[]}");
            assertThat(svc.getCachedRoadLayer()).isPresent();
        }

        @Test
        void esaAndRoadCachesDoNotConflict(@TempDir Path tempDir) throws IOException {
            IngestionCacheService svc = new IngestionCacheService(tempDir.toString());
            svc.storeEsaLayer("esa-bytes".getBytes());
            // Road cache should still be empty
            assertThat(svc.getCachedRoadLayer()).isEmpty();
        }
    }

    // ─── OsmRoadLoaderService ─────────────────────────────────────────────────

    @Nested
    class OsmRoadLoaderServiceTest {

        private static final String TRACK_FEATURE = """
                {
                  "type": "FeatureCollection",
                  "features": [
                    {
                      "type": "Feature",
                      "properties": { "highway": "track" },
                      "geometry": {
                        "type": "LineString",
                        "coordinates": [[260000.0, 9860000.0], [260100.0, 9860100.0], [260200.0, 9860050.0]]
                      }
                    }
                  ]
                }
                """;

        private static final String MIXED_HIGHWAY_FEATURES = """
                {
                  "type": "FeatureCollection",
                  "features": [
                    {
                      "type": "Feature",
                      "properties": { "highway": "track" },
                      "geometry": { "type": "LineString",
                        "coordinates": [[0.0, 0.0], [100.0, 0.0]] }
                    },
                    {
                      "type": "Feature",
                      "properties": { "highway": "motorway" },
                      "geometry": { "type": "LineString",
                        "coordinates": [[200.0, 0.0], [300.0, 0.0]] }
                    }
                  ]
                }
                """;

        @Test
        void missingFile_returnsEmptyRoadLayer(@TempDir Path tempDir) {
            OsmRoadLoaderService svc = new OsmRoadLoaderService(
                    tempDir.resolve("nonexistent.geojson").toString(),
                    new ObjectMapper());
            RoadLayer layer = svc.load();
            assertThat(layer.isEmpty()).isTrue();
        }

        @Test
        void validFile_parsesSegments(@TempDir Path tempDir) throws IOException {
            Path file = tempDir.resolve("roads.geojson");
            Files.writeString(file, TRACK_FEATURE);

            OsmRoadLoaderService svc = new OsmRoadLoaderService(
                    file.toString(), new ObjectMapper());
            RoadLayer layer = svc.load();

            assertThat(layer.isEmpty()).isFalse();
            assertThat(layer.getSegments()).hasSize(1);
            assertThat(layer.getSegments().get(0)).hasNumberOfRows(3); // 3 vertices
        }

        @Test
        void nonAcceptedHighwayTags_areFiltered(@TempDir Path tempDir) throws IOException {
            Path file = tempDir.resolve("roads.geojson");
            Files.writeString(file, MIXED_HIGHWAY_FEATURES);

            OsmRoadLoaderService svc = new OsmRoadLoaderService(
                    file.toString(), new ObjectMapper());
            RoadLayer layer = svc.load();

            // Only "track" accepted; "motorway" filtered out
            assertThat(layer.getSegments()).hasSize(1);
        }

        @Test
        void coordinatesAreReadCorrectly(@TempDir Path tempDir) throws IOException {
            Path file = tempDir.resolve("roads.geojson");
            Files.writeString(file, TRACK_FEATURE);

            OsmRoadLoaderService svc = new OsmRoadLoaderService(
                    file.toString(), new ObjectMapper());
            RoadLayer layer = svc.load();

            double[][] segment = layer.getSegments().get(0);
            assertThat(segment[0][0]).isCloseTo(260000.0, within(0.1));
            assertThat(segment[0][1]).isCloseTo(9860000.0, within(0.1));
        }
    }

}