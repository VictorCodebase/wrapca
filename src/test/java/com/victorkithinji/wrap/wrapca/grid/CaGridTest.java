package com.victorkithinji.wrap.wrapca.grid;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CaGrid}.
 * <p>
 * Organised into nested classes by concern: construction contracts,
 * index encoding helpers, state accessors, bounds checks, and deep copy.
 */
class CaGridTest {

	// -------------------------------------------------------------------------
	// Shared grid factory — 5x4 grid, 100m cells, all UNBURNED
	// -------------------------------------------------------------------------

	private static final int ROWS = 5;
	private static final int COLS = 4;
	private static final double CELL_SIZE = 100.0;

	private static CellEnvironment defaultEnv() {
		return new CellEnvironment(
			0.60f, 0.35f, 0.0f, 0.0f, 0.0f, VegetationTypeEnum.SHRUBLAND
		);
	}

	private static CaGrid freshGrid() {
		int[][] states = new int[ROWS][COLS]; // JVM zero-fills → all UNBURNED
		CellEnvironment[][] env = new CellEnvironment[ROWS][COLS];
		CellEnvironment cell = defaultEnv();
		for (int r = 0; r < ROWS; r++)
			for (int c = 0; c < COLS; c++)
				env[r][c] = cell;
		return new CaGrid(states, env, ROWS, COLS, CELL_SIZE);
	}

	// =========================================================================
	@Nested
	class Construction {
		// =========================================================================

		@Test
		void validArguments_constructsSuccessfully() {
			CaGrid grid = freshGrid();
			assertEquals(ROWS, grid.rows);
			assertEquals(COLS, grid.cols);
			assertEquals(CELL_SIZE, grid.cellSizeMetres);
		}

		@Test
		void jvmZeroInit_meansAllCellsStartUnburned() {
			CaGrid grid = freshGrid();
			for (int r = 0; r < ROWS; r++)
				for (int c = 0; c < COLS; c++)
					assertEquals(CellStateEnum.UNBURNED, grid.getState(r, c),
						"Cell [" + r + "][" + c + "] should be UNBURNED on a zero-init grid");
		}

		@Test
		void zeroRows_throwsIllegalArgumentException() {
			assertThrows(IllegalArgumentException.class, () ->
				new CaGrid(new int[0][4], new CellEnvironment[0][4], 0, 4, 100.0));
		}

		@Test
		void zeroCols_throwsIllegalArgumentException() {
			assertThrows(IllegalArgumentException.class, () ->
				new CaGrid(new int[4][0], new CellEnvironment[4][0], 4, 0, 100.0));
		}

		@Test
		void negativeCellSize_throwsIllegalArgumentException() {
			assertThrows(IllegalArgumentException.class, () ->
				new CaGrid(new int[3][3], new CellEnvironment[3][3], 3, 3, -1.0));
		}

		@Test
		void zeroCellSize_throwsIllegalArgumentException() {
			assertThrows(IllegalArgumentException.class, () ->
				new CaGrid(new int[3][3], new CellEnvironment[3][3], 3, 3, 0.0));
		}

		@Test
		void stateArrayMismatch_throwsIllegalArgumentException() {
			// States array claims 3 rows but constructor is told rows=5
			assertThrows(IllegalArgumentException.class, () ->
				new CaGrid(new int[3][4], new CellEnvironment[5][4], 5, 4, 100.0));
		}

		@Test
		void envArrayMismatch_throwsIllegalArgumentException() {
			assertThrows(IllegalArgumentException.class, () ->
				new CaGrid(new int[5][4], new CellEnvironment[3][4], 5, 4, 100.0));
		}
	}

	// =========================================================================
	@Nested
	class IndexEncoding {
		// =========================================================================

		private CaGrid grid;

		@BeforeEach
		void setup() {
			grid = freshGrid();
		}

		@Test
		void encodeIndex_topLeftCell_isZero() {
			assertEquals(0L, grid.encodeIndex(0, 0));
		}

		@Test
		void encodeIndex_firstRowSecondCol() {
			// row=0, col=1 → 0*4 + 1 = 1
			assertEquals(1L, grid.encodeIndex(0, 1));
		}

		@Test
		void encodeIndex_secondRowFirstCol() {
			// row=1, col=0 → 1*4 + 0 = 4
			assertEquals(4L, grid.encodeIndex(1, 0));
		}

		@Test
		void encodeIndex_bottomRightCell() {
			// row=4, col=3 → 4*4 + 3 = 19
			assertEquals(19L, grid.encodeIndex(4, 3));
		}

		@Test
		void decodeRow_roundTrip() {
			for (int r = 0; r < ROWS; r++) {
				for (int c = 0; c < COLS; c++) {
					long idx = grid.encodeIndex(r, c);
					assertEquals(r, grid.decodeRow(idx),
						"Row round-trip failed for [" + r + "][" + c + "]");
				}
			}
		}

		@Test
		void decodeCol_roundTrip() {
			for (int r = 0; r < ROWS; r++) {
				for (int c = 0; c < COLS; c++) {
					long idx = grid.encodeIndex(r, c);
					assertEquals(c, grid.decodeCol(idx),
						"Col round-trip failed for [" + r + "][" + c + "]");
				}
			}
		}

		@Test
		void allIndices_areUnique() {
			// Duplicate indices would cause cells to collide in HashSet<Long> frontiers
			java.util.Set<Long> seen = new java.util.HashSet<>();
			for (int r = 0; r < ROWS; r++)
				for (int c = 0; c < COLS; c++)
					assertTrue(seen.add(grid.encodeIndex(r, c)),
						"Duplicate index at [" + r + "][" + c + "]");
		}
	}

	// =========================================================================
	@Nested
	class StateAccessors {
		// =========================================================================

		private CaGrid grid;

		@BeforeEach
		void setup() {
			grid = freshGrid();
		}

		@Test
		void setState_thenGetState_returnsNewState() {
			grid.setState(2, 3, CellStateEnum.BURNING);
			assertEquals(CellStateEnum.BURNING, grid.getState(2, 3));
		}

		@Test
		void setState_doesNotAffectOtherCells() {
			grid.setState(1, 1, CellStateEnum.BURNED);
			// Adjacent cell must be untouched
			assertEquals(CellStateEnum.UNBURNED, grid.getState(1, 2));
			assertEquals(CellStateEnum.UNBURNED, grid.getState(0, 1));
		}

		@Test
		void setState_nonCombustible_persists() {
			grid.setState(0, 0, CellStateEnum.NON_COMBUSTIBLE);
			assertEquals(CellStateEnum.NON_COMBUSTIBLE, grid.getState(0, 0));
		}

		@Test
		void rawOrdinalAndGetState_areConsistent() {
			grid.setState(3, 2, CellStateEnum.BURNING);
			int rawOrdinal = grid.states[3][2];
			assertEquals(CellStateEnum.BURNING, CellStateEnum.values()[rawOrdinal]);
		}
	}

	// =========================================================================
	@Nested
	class BoundsCheck {
		// =========================================================================

		private CaGrid grid;

		@BeforeEach
		void setup() {
			grid = freshGrid();
		}

		@Test
		void inBounds_topLeftCorner_true() {
			assertTrue(grid.inBounds(0, 0));
		}

		@Test
		void inBounds_bottomRightCorner_true() {
			assertTrue(grid.inBounds(ROWS - 1, COLS - 1));
		}

		@Test
		void inBounds_negativeRow_false() {
			assertFalse(grid.inBounds(-1, 0));
		}

		@Test
		void inBounds_negativeCol_false() {
			assertFalse(grid.inBounds(0, -1));
		}

		@Test
		void inBounds_rowEqualsRows_false() {
			assertFalse(grid.inBounds(ROWS, 0));
		}

		@Test
		void inBounds_colEqualsCols_false() {
			assertFalse(grid.inBounds(0, COLS));
		}
	}

	// =========================================================================
	@Nested
	class DeepCopy {
		// =========================================================================

		@Test
		void deepCopy_hasSameDimensions() {
			CaGrid original = freshGrid();
			CaGrid copy = original.deepCopy();
			assertEquals(original.rows, copy.rows);
			assertEquals(original.cols, copy.cols);
			assertEquals(original.cellSizeMetres, copy.cellSizeMetres);
		}

		@Test
		void deepCopy_stateArrayIsIndependent() {
			CaGrid original = freshGrid();
			CaGrid copy = original.deepCopy();

			// Mutate the copy — original must be unaffected
			copy.setState(0, 0, CellStateEnum.BURNING);
			assertEquals(CellStateEnum.UNBURNED, original.getState(0, 0),
				"Mutating the copy must not change the original (Monte Carlo isolation)");
		}

		@Test
		void deepCopy_originalMutationDoesNotAffectCopy() {
			CaGrid original = freshGrid();
			CaGrid copy = original.deepCopy();

			original.setState(2, 2, CellStateEnum.BURNED);
			assertEquals(CellStateEnum.UNBURNED, copy.getState(2, 2),
				"Mutating the original must not change the copy");
		}

		@Test
		void deepCopy_preservesExistingState() {
			CaGrid original = freshGrid();
			original.setState(1, 1, CellStateEnum.BURNING);
			original.setState(3, 3, CellStateEnum.NON_COMBUSTIBLE);

			CaGrid copy = original.deepCopy();
			assertEquals(CellStateEnum.BURNING, copy.getState(1, 1));
			assertEquals(CellStateEnum.NON_COMBUSTIBLE, copy.getState(3, 3));
		}

		@Test
		void deepCopy_environmentReferencesAreSameObjects() {
			// CellEnvironment is immutable (@Value) so sharing references is safe
			// and avoids an expensive element-wise clone of the env array.
			CaGrid original = freshGrid();
			CaGrid copy = original.deepCopy();
			assertSame(original.environment[0][0], copy.environment[0][0],
				"Environment objects are immutable — sharing references is correct and expected");
		}
	}
}