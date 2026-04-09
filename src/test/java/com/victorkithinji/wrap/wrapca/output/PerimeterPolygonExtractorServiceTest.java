package com.victorkithinji.wrap.wrapca.output;

import com.victorkithinji.wrap.wrapca.grid.CaGrid;
import com.victorkithinji.wrap.wrapca.grid.CellStateEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link PerimeterPolygonExtractorService}.
 * <p>
 * No Spring context — the service has no injected dependencies.
 * Tests focus on:
 * 1. GeoJSON structural validity (type, features array, geometry type)
 * 2. Boundary cell detection logic across all state combinations
 * 3. Grid-edge handling (cells at the edge are always boundary cells)
 * 4. Coordinate convention ([col, row] = [x, y])
 * 5. Polygon ring closure (first == last coordinate pair)
 * 6. Timestamp embedding
 * 7. Empty / degenerate grids
 */
@DisplayName("PerimeterPolygonExtractorService")
class PerimeterPolygonExtractorServiceTest {

	private PerimeterPolygonExtractorService extractor;
	private static final Instant T0 = Instant.parse("2025-06-15T08:00:00Z");

	@BeforeEach
	void setUp() {
		extractor = new PerimeterPolygonExtractorService();
	}

	// -------------------------------------------------------------------------
	// Empty / no-fire cases
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("when no fire cells exist")
	class NoFireCells {

		@Test
		@DisplayName("all-UNBURNED grid returns empty FeatureCollection")
		void allUnburned_returnsEmptyFeatureCollection() {
			CaGrid grid = GridTestFactory.allUnburned(5, 5);
			String json = extractor.extract(grid, T0);

			assertThat(json).contains("\"type\":\"FeatureCollection\"");
			assertThat(json).contains("\"features\":[]");
		}

		@Test
		@DisplayName("all-NON_COMBUSTIBLE grid returns empty FeatureCollection")
		void allNonCombustible_returnsEmptyFeatureCollection() {
			CaGrid grid = GridTestFactory.uniformState(3, 3, CellStateEnum.NON_COMBUSTIBLE);
			String json = extractor.extract(grid, T0);

			assertThat(json).contains("\"features\":[]");
		}

		@Test
		@DisplayName("1x1 UNBURNED grid returns empty FeatureCollection")
		void singleUnburnedCell_returnsEmpty() {
			CaGrid grid = GridTestFactory.singleCell(CellStateEnum.UNBURNED);
			String json = extractor.extract(grid, T0);

			assertThat(json).contains("\"features\":[]");
		}

		@Test
		@DisplayName("timestamp is embedded in empty FeatureCollection")
		void emptyCollection_embedsTimestamp() {
			CaGrid grid = GridTestFactory.allUnburned(2, 2);
			String json = extractor.extract(grid, T0);

			assertThat(json).contains("\"timestamp\":\"" + T0 + "\"");
		}
	}

	// -------------------------------------------------------------------------
	// Structural / GeoJSON validity
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("GeoJSON structure")
	class GeoJsonStructure {

		@Test
		@DisplayName("root type is FeatureCollection")
		void rootTypeIsFeatureCollection() {
			CaGrid grid = GridTestFactory.withStateAt(3, 3, 1, 1, CellStateEnum.BURNING);
			String json = extractor.extract(grid, T0);

			assertThat(json).startsWith("{\"type\":\"FeatureCollection\"");
		}

		@Test
		@DisplayName("each feature has type Feature")
		void eachFeatureHasTypeFeature() {
			CaGrid grid = GridTestFactory.withStateAt(3, 3, 1, 1, CellStateEnum.BURNING);
			String json = extractor.extract(grid, T0);

			assertThat(json).contains("\"type\":\"Feature\"");
		}

		@Test
		@DisplayName("geometry type is Polygon")
		void geometryTypeIsPolygon() {
			CaGrid grid = GridTestFactory.withStateAt(3, 3, 1, 1, CellStateEnum.BURNING);
			String json = extractor.extract(grid, T0);

			assertThat(json).contains("\"type\":\"Polygon\"");
		}

		@Test
		@DisplayName("properties object is present and empty")
		void propertiesObjectPresent() {
			CaGrid grid = GridTestFactory.withStateAt(3, 3, 1, 1, CellStateEnum.BURNING);
			String json = extractor.extract(grid, T0);

			assertThat(json).contains("\"properties\":{}");
		}

		@Test
		@DisplayName("timestamp is embedded in non-empty FeatureCollection")
		void nonEmptyCollection_embedsTimestamp() {
			CaGrid grid = GridTestFactory.withStateAt(3, 3, 0, 0, CellStateEnum.BURNING);
			String json = extractor.extract(grid, T0);

			assertThat(json).contains("\"timestamp\":\"" + T0 + "\"");
		}

		@Test
		@DisplayName("distinct timestamp values produce distinct outputs")
		void distinctTimestamps() {
			CaGrid grid = GridTestFactory.withStateAt(3, 3, 1, 1, CellStateEnum.BURNING);
			Instant t1 = Instant.parse("2025-06-15T10:00:00Z");
			Instant t2 = Instant.parse("2025-06-15T11:00:00Z");

			String json1 = extractor.extract(grid, t1);
			String json2 = extractor.extract(grid, t2);

			assertThat(json1).isNotEqualTo(json2);
			assertThat(json1).contains(t1.toString());
			assertThat(json2).contains(t2.toString());
		}
	}

	// -------------------------------------------------------------------------
	// Boundary cell detection — interior cells
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("boundary cell detection — interior cells")
	class InteriorBoundaryDetection {

		@Test
		@DisplayName("single BURNING cell surrounded by UNBURNED is a boundary cell")
		void singleBurningInterior_isBoundary() {
			// 3x3, centre cell BURNING, all others UNBURNED
			CaGrid grid = GridTestFactory.withStateAt(3, 3, 1, 1, CellStateEnum.BURNING);
			String json = extractor.extract(grid, T0);

			assertThat(json).doesNotContain("\"features\":[]");
			assertThat(countFeatures(json)).isEqualTo(1);
		}

		@Test
		@DisplayName("single BURNED cell surrounded by UNBURNED is a boundary cell")
		void singleBurnedInterior_isBoundary() {
			CaGrid grid = GridTestFactory.withStateAt(3, 3, 1, 1, CellStateEnum.BURNED);
			String json = extractor.extract(grid, T0);

			assertThat(countFeatures(json)).isEqualTo(1);
		}

		@Test
		@DisplayName("cell surrounded entirely by BURNING/BURNED is not a boundary cell")
		void cellSurroundedByFire_isNotBoundary() {
			// 3x3 all BURNED — only the edge cells are boundary cells
			CaGrid grid = GridTestFactory.uniformState(3, 3, CellStateEnum.BURNED);
			String json = extractor.extract(grid, T0);

			// The centre cell (1,1) has no non-fire neighbours and no edge exposure
			// so it should NOT appear as a boundary. Only the 8 outer cells should.
			int features = countFeatures(json);
			assertThat(features).isEqualTo(8); // all outer cells; centre excluded
		}

		@Test
		@DisplayName("5x5 all-BURNED: only outer ring (16 cells) are boundary cells")
		void fiveByFiveAllBurned_outerRingOnly() {
			CaGrid grid = GridTestFactory.uniformState(5, 5, CellStateEnum.BURNED);
			String json = extractor.extract(grid, T0);

			// Outer ring = 5*4 - 4 = 16 cells
			assertThat(countFeatures(json)).isEqualTo(16);
		}

		@Test
		@DisplayName("BURNING cell adjacent to NON_COMBUSTIBLE is a boundary cell")
		void burningAdjacentToNonCombustible_isBoundary() {
			// 1x2: col0=BURNING, col1=NON_COMBUSTIBLE
			CaGrid grid = GridTestFactory.build(1, 2,
				(r, c) -> c == 0 ? CellStateEnum.BURNING : CellStateEnum.NON_COMBUSTIBLE,
				(r, c) -> GridTestFactory.DEFAULT_VEG);
			String json = extractor.extract(grid, T0);

			// BURNING cell at (0,0) has a NON_COMBUSTIBLE neighbour → boundary
			assertThat(countFeatures(json)).isEqualTo(1);
		}
	}

	// -------------------------------------------------------------------------
	// Grid edge handling
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("grid edge handling")
	class GridEdgeHandling {

		@Test
		@DisplayName("BURNING cell at corner is always a boundary cell")
		void burningAtCorner_isBoundary() {
			CaGrid grid = GridTestFactory.withStateAt(1, 1, 0, 0, CellStateEnum.BURNING);
			String json = extractor.extract(grid, T0);

			assertThat(countFeatures(json)).isEqualTo(1);
		}

		@Test
		@DisplayName("1x1 BURNING grid: single cell is a boundary cell")
		void oneByOneBurning_isBoundaryCell() {
			CaGrid grid = GridTestFactory.singleCell(CellStateEnum.BURNING);
			String json = extractor.extract(grid, T0);

			assertThat(countFeatures(json)).isEqualTo(1);
		}

		@Test
		@DisplayName("1x1 BURNED grid: single cell is a boundary cell")
		void oneByOneBurned_isBoundaryCell() {
			CaGrid grid = GridTestFactory.singleCell(CellStateEnum.BURNED);
			String json = extractor.extract(grid, T0);

			assertThat(countFeatures(json)).isEqualTo(1);
		}

		@Test
		@DisplayName("single-row grid: all fire cells are boundary cells")
		void singleRow_allFireCellsAreBoundary() {
			// 1x5, all BURNED
			CaGrid grid = GridTestFactory.uniformState(1, 5, CellStateEnum.BURNED);
			String json = extractor.extract(grid, T0);

			// All 5 cells are on the grid edge → all boundary
			assertThat(countFeatures(json)).isEqualTo(5);
		}

		@Test
		@DisplayName("single-column grid: all fire cells are boundary cells")
		void singleColumn_allFireCellsAreBoundary() {
			CaGrid grid = GridTestFactory.uniformState(5, 1, CellStateEnum.BURNED);
			String json = extractor.extract(grid, T0);

			assertThat(countFeatures(json)).isEqualTo(5);
		}

		@Test
		@DisplayName("BURNING cell at top-left corner of larger grid is a boundary cell")
		void burningTopLeft_isBoundary() {
			CaGrid grid = GridTestFactory.withStateAt(5, 5, 0, 0, CellStateEnum.BURNING);
			String json = extractor.extract(grid, T0);

			assertThat(countFeatures(json)).isEqualTo(1);
		}

		@Test
		@DisplayName("BURNING cell at bottom-right corner of larger grid is a boundary cell")
		void burningBottomRight_isBoundary() {
			CaGrid grid = GridTestFactory.withStateAt(5, 5, 4, 4, CellStateEnum.BURNING);
			String json = extractor.extract(grid, T0);

			assertThat(countFeatures(json)).isEqualTo(1);
		}
	}

	// -------------------------------------------------------------------------
	// Coordinate convention and polygon ring closure
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("coordinate convention and polygon geometry")
	class CoordinateConvention {

		@Test
		@DisplayName("cell at (row=0, col=0) produces polygon with corners [0,0],[1,0],[1,1],[0,1],[0,0]")
		void cellAtOrigin_correctCoordinates() {
			CaGrid grid = GridTestFactory.singleCell(CellStateEnum.BURNING);
			String json = extractor.extract(grid, T0);

			// Expected ring: [0,0],[1,0],[1,1],[0,1],[0,0]
			assertThat(json).contains("[0,0]");
			assertThat(json).contains("[1,0]");
			assertThat(json).contains("[1,1]");
			assertThat(json).contains("[0,1]");
		}

		@Test
		@DisplayName("polygon ring is closed — first and last coordinate are identical")
		void polygonRingIsClosed() {
			CaGrid grid = GridTestFactory.singleCell(CellStateEnum.BURNING);
			String json = extractor.extract(grid, T0);

			// Ring opens and closes at [0,0] for the (0,0) cell
			// The string [0,0] must appear at least twice (open and close)
			int firstIndex = json.indexOf("[0,0]");
			int lastIndex = json.lastIndexOf("[0,0]");
			assertThat(firstIndex).isNotEqualTo(lastIndex);
		}

		@Test
		@DisplayName("cell at (row=2, col=3) produces polygon with col offset 3 and row offset 2")
		void cellOffOrigin_correctCoordinates() {
			// 5x5, fire cell at row=2, col=3
			CaGrid grid = GridTestFactory.withStateAt(5, 5, 2, 3, CellStateEnum.BURNING);
			String json = extractor.extract(grid, T0);

			// Bottom-left of this cell in GeoJSON x,y = [3,2]; top-right = [4,3]
			assertThat(json).contains("[3,2]");
			assertThat(json).contains("[4,2]");
			assertThat(json).contains("[4,3]");
			assertThat(json).contains("[3,3]");
		}

		@Test
		@DisplayName("coordinates use [col, row] order — x before y")
		void coordinatesAreColThenRow() {
			// Place fire at row=1, col=0 in a 3x3 grid
			CaGrid grid = GridTestFactory.withStateAt(3, 3, 1, 0, CellStateEnum.BURNING);
			String json = extractor.extract(grid, T0);

			// GeoJSON x=col=0, y=row=1 → first corner is [0,1]
			assertThat(json).contains("[0,1]");
			// And NOT [1,0] as the first corner (which would be row-first)
			// The ring corners should be [0,1],[1,1],[1,2],[0,2],[0,1]
			assertThat(json).contains("[1,1]");
			assertThat(json).contains("[1,2]");
			assertThat(json).contains("[0,2]");
		}
	}

	// -------------------------------------------------------------------------
	// State combinations — which states qualify as "fire" for boundary detection
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("state qualification")
	class StateQualification {

		@Test
		@DisplayName("UNBURNED cells are never boundary cells")
		void unburnedCells_neverBoundary() {
			CaGrid grid = GridTestFactory.allUnburned(4, 4);
			String json = extractor.extract(grid, T0);
			assertThat(json).contains("\"features\":[]");
		}

		@Test
		@DisplayName("NON_COMBUSTIBLE cells are never boundary cells")
		void nonCombustibleCells_neverBoundary() {
			CaGrid grid = GridTestFactory.uniformState(4, 4, CellStateEnum.NON_COMBUSTIBLE);
			String json = extractor.extract(grid, T0);
			assertThat(json).contains("\"features\":[]");
		}

		@Test
		@DisplayName("BURNING cells qualify as fire cells")
		void burningCells_qualifyAsFire() {
			CaGrid grid = GridTestFactory.withStateAt(3, 3, 1, 1, CellStateEnum.BURNING);
			String json = extractor.extract(grid, T0);
			assertThat(json).doesNotContain("\"features\":[]");
		}

		@Test
		@DisplayName("BURNED cells qualify as fire cells")
		void burnedCells_qualifyAsFire() {
			CaGrid grid = GridTestFactory.withStateAt(3, 3, 1, 1, CellStateEnum.BURNED);
			String json = extractor.extract(grid, T0);
			assertThat(json).doesNotContain("\"features\":[]");
		}

		@Test
		@DisplayName("mixed BURNING and BURNED cells both produce boundary features")
		void mixedBurningAndBurned_bothProduceBoundaryFeatures() {
			// 1x3: BURNING | BURNED | UNBURNED
			CaGrid grid = GridTestFactory.build(1, 3,
				(r, c) -> switch (c) {
					case 0 -> CellStateEnum.BURNING;
					case 1 -> CellStateEnum.BURNED;
					default -> CellStateEnum.UNBURNED;
				},
				(r, c) -> GridTestFactory.DEFAULT_VEG);
			String json = extractor.extract(grid, T0);

			// Both col 0 and col 1 are fire cells with non-fire neighbours
			assertThat(countFeatures(json)).isEqualTo(2);
		}
	}

	// -------------------------------------------------------------------------
	// Multiple boundary cells — feature count
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("multiple boundary cells")
	class MultipleBoundaryCells {

		@Test
		@DisplayName("L-shaped fire perimeter produces correct feature count")
		void lShapedFire_correctFeatureCount() {
			// 3x3 grid with fire in top row and left column (L-shape excluding centre)
			//   F F F
			//   F . .
			//   F . .
			// Fire cells: (0,0),(0,1),(0,2),(1,0),(2,0) = 5 cells, all boundary
			CaGrid grid = GridTestFactory.build(3, 3,
				(r, c) -> (r == 0 || c == 0) ? CellStateEnum.BURNED : CellStateEnum.UNBURNED,
				(r, c) -> GridTestFactory.DEFAULT_VEG);
			String json = extractor.extract(grid, T0);

			assertThat(countFeatures(json)).isEqualTo(5);
		}

		@Test
		@DisplayName("fire ring around perimeter of 4x4 grid: 12 boundary cells")
		void fireRing_correctCount() {
			// Outer ring BURNING, interior UNBURNED
			//  F F F F
			//  F . . F
			//  F . . F
			//  F F F F
			CaGrid grid = GridTestFactory.build(4, 4,
				(r, c) -> (r == 0 || r == 3 || c == 0 || c == 3)
					? CellStateEnum.BURNING : CellStateEnum.UNBURNED,
				(r, c) -> GridTestFactory.DEFAULT_VEG);
			String json = extractor.extract(grid, T0);

			// All 12 outer cells have UNBURNED neighbours (interior) or grid edge
			assertThat(countFeatures(json)).isEqualTo(12);
		}

		@Test
		@DisplayName("two isolated fire cells produce two features")
		void twoIsolatedFireCells_twoFeatures() {
			// 5x5: fire at (0,0) and (4,4), separated
			CaGrid grid = GridTestFactory.build(5, 5,
				(r, c) -> (r == 0 && c == 0) || (r == 4 && c == 4)
					? CellStateEnum.BURNING : CellStateEnum.UNBURNED,
				(r, c) -> GridTestFactory.DEFAULT_VEG);
			String json = extractor.extract(grid, T0);

			assertThat(countFeatures(json)).isEqualTo(2);
		}
	}

	// -------------------------------------------------------------------------
	// Idempotency
	// -------------------------------------------------------------------------

	@Test
	@DisplayName("calling extract twice on the same grid returns identical output")
	void idempotent() {
		CaGrid grid = GridTestFactory.withStateAt(5, 5, 2, 2, CellStateEnum.BURNING);
		String first = extractor.extract(grid, T0);
		String second = extractor.extract(grid, T0);
		assertThat(first).isEqualTo(second);
	}

	// -------------------------------------------------------------------------
	// Helpers
	// -------------------------------------------------------------------------

	/**
	 * Counts the number of Feature objects in the JSON by counting occurrences of "\"type\":\"Feature\"".
	 */
	private int countFeatures(String json) {
		int count = 0;
		int idx = 0;
		String marker = "\"type\":\"Feature\"";
		while ((idx = json.indexOf(marker, idx)) != -1) {
			count++;
			idx += marker.length();
		}
		return count;
	}
}