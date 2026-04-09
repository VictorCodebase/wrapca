package com.victorkithinji.wrap.wrapca.output;

import com.victorkithinji.wrap.wrapca.grid.CaGrid;
import com.victorkithinji.wrap.wrapca.grid.CellEnvironment;
import com.victorkithinji.wrap.wrapca.grid.CellStateEnum;
import com.victorkithinji.wrap.wrapca.grid.VegetationTypeEnum;

/**
 * Factory for building {@link CaGrid} instances in tests.
 * Centralises grid construction so individual test methods stay focused on
 * the behaviour under test, not on boilerplate setup.
 */
class GridTestFactory {

	/**
	 * Default vegetation type used when a test does not care about the specific type.
	 */
	static final VegetationTypeEnum DEFAULT_VEG = VegetationTypeEnum.GRASSLAND;

	/**
	 * Creates a grid of the given dimensions where every cell is UNBURNED
	 * and uses the default vegetation type.
	 */
	static CaGrid allUnburned(int rows, int cols) {
		return build(rows, cols, (r, c) -> CellStateEnum.UNBURNED, (r, c) -> DEFAULT_VEG);
	}

	/**
	 * Creates a 1×1 grid with a single cell in the given state.
	 */
	static CaGrid singleCell(CellStateEnum state) {
		return build(1, 1, (r, c) -> state, (r, c) -> DEFAULT_VEG);
	}

	/**
	 * Creates a grid where every cell is set to the given state.
	 */
	static CaGrid uniformState(int rows, int cols, CellStateEnum state) {
		return build(rows, cols, (r, c) -> state, (r, c) -> DEFAULT_VEG);
	}

	/**
	 * Creates a grid with a specific state at one cell and UNBURNED everywhere else.
	 */
	static CaGrid withStateAt(int rows, int cols, int targetRow, int targetCol, CellStateEnum state) {
		return build(rows, cols,
			(r, c) -> (r == targetRow && c == targetCol) ? state : CellStateEnum.UNBURNED,
			(r, c) -> DEFAULT_VEG);
	}

	/**
	 * Creates a grid with a specific vegetation type at every cell.
	 */
	static CaGrid uniformVeg(int rows, int cols, VegetationTypeEnum veg) {
		return build(rows, cols, (r, c) -> CellStateEnum.UNBURNED, (r, c) -> veg);
	}

	/**
	 * Creates a grid where the vegetation type is determined per-cell by the supplier.
	 */
	static CaGrid withVegGrid(int rows, int cols, VegSupplier vegSupplier) {
		return build(rows, cols, (r, c) -> CellStateEnum.UNBURNED, vegSupplier);
	}

	/**
	 * Full builder — both state and vegetation type determined per-cell.
	 */
	static CaGrid build(int rows, int cols, StateSupplier stateSupplier, VegSupplier vegSupplier) {
		int[][] states = new int[rows][cols];
		CellEnvironment[][] env = new CellEnvironment[rows][cols];

		for (int r = 0; r < rows; r++) {
			for (int c = 0; c < cols; c++) {
				states[r][c] = stateSupplier.get(r, c).ordinal();
				env[r][c] = new CellEnvironment(
					0.5f,                        // ndvi
					0.1f,                        // ndmi
					1200f,                       // elevationMetres
					0.1f,                        // slopeRadians
					1.5f,                        // aspectRadians
					vegSupplier.get(r, c)
				);
			}
		}

		return new CaGrid(states, env, rows, cols, 100.0);
	}

	@FunctionalInterface
	interface StateSupplier {
		CellStateEnum get(int row, int col);
	}

	@FunctionalInterface
	interface VegSupplier {
		VegetationTypeEnum get(int row, int col);
	}
}