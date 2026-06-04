package com.victorkithinji.wrap.wrapca.ingestion;

import com.victorkithinji.wrap.wrapca.config.SimulationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for RasterResamplerService.
 * No Spring context — SimulationConfig constructed directly.
 */
class RasterResamplerServiceTest {

	private RasterResamplerService service;   // default 100m target

	@BeforeEach
	void setUp() {
		service = serviceWithTarget(100.0);
	}

	// =========================================================================
	// resample(GridBands) — continuous bands
	// =========================================================================

	@Test
	void continuousBands_outputDimensionsCorrect() {
		GridBands result = service.resample(uniformGridBands(200, 200, 10.0, 0.5f));
		assertThat(result.getRows()).isEqualTo(20);
		assertThat(result.getCols()).isEqualTo(20);
		assertThat(result.getCellSizeMetres()).isEqualTo(100.0);
	}

	@Test
	void continuousBands_uniformSourceProducesUniformOutput() {
		GridBands result = service.resample(uniformGridBands(100, 100, 10.0, 0.72f));
		for (int r = 0; r < result.getRows(); r++)
			for (int c = 0; c < result.getCols(); c++)
				assertThat(result.getNdvi()[r][c]).as("[%d][%d]", r, c)
					.isEqualTo(0.72f, within(1e-5f));
	}

	@Test
	void continuousBands_blockMeanIsCorrect() {
		// 2x2 at 10m → 1x1 at 20m; mean of 0.2, 0.4, 0.6, 0.8 = 0.5
		float[][] ndvi = {{0.2f, 0.4f}, {0.6f, 0.8f}};
		GridBands native_ = gridBandsWithNdvi(2, 2, 10.0, ndvi);
		GridBands result = serviceWithTarget(20.0).resample(native_);

		assertThat(result.getRows()).isEqualTo(1);
		assertThat(result.getCols()).isEqualTo(1);
		assertThat(result.getNdvi()[0][0]).isEqualTo(0.5f, within(1e-5f));
	}

	@Test
	void continuousBands_allSixBandsResampled() {
		GridBands native_ = new GridBands(
			filled(2, 2, 0.7f), filled(2, 2, 0.3f), filled(2, 2, 0.08f),
			filled(2, 2, 1500f), filled(2, 2, 15.0f), filled(2, 2, 1.57f),
			2, 2, 10.0, 0, 0, 20, 20);
		GridBands result = serviceWithTarget(20.0).resample(native_);

		assertThat(result.getNdvi()[0][0]).isEqualTo(0.7f, within(1e-5f));
		assertThat(result.getNdmi()[0][0]).isEqualTo(0.3f, within(1e-5f));
		assertThat(result.getFmc()[0][0]).isEqualTo(0.08f, within(1e-5f));
		assertThat(result.getElevationMetres()[0][0]).isEqualTo(1500f, within(0.1f));
		assertThat(result.getSlopeDegrees()[0][0]).isEqualTo(15.0f, within(1e-4f));
		assertThat(result.getAspectRadians()[0][0]).isEqualTo(1.57f, within(1e-4f));
	}

	@Test
	void continuousBands_boundingBoxPreserved() {
		GridBands native_ = new GridBands(
			filled(100, 100, 0.5f), filled(100, 100, 0.2f), filled(100, 100, 0.08f),
			filled(100, 100, 1000f), filled(100, 100, 5f), filled(100, 100, 0.5f),
			100, 100, 10.0, 500000, 9800000, 501000, 9801000);
		GridBands result = service.resample(native_);

		assertThat(result.getMinX()).isEqualTo(500000);
		assertThat(result.getMinY()).isEqualTo(9800000);
		assertThat(result.getMaxX()).isEqualTo(501000);
		assertThat(result.getMaxY()).isEqualTo(9801000);
	}

	@Test
	void continuousBands_noOpWhenCellSizeMatches() {
		GridBands native_ = uniformGridBands(20, 20, 100.0, 0.4f);
		assertThat(service.resample(native_)).isSameAs(native_);
	}

	@Test
	void continuousBands_partialEdgeBlockDoesNotThrow() {
		assertThatCode(() -> service.resample(uniformGridBands(15, 15, 10.0, 0.5f)))
			.doesNotThrowAnyException();
	}

	@Test
	void continuousBands_upsamplingThrows() {
		GridBands native_ = uniformGridBands(100, 100, 10.0, 0.5f);
		assertThatThrownBy(() -> serviceWithTarget(5.0).resample(native_))
			.isInstanceOf(IllegalArgumentException.class);
	}

	// =========================================================================
	// resampleEsa(EsaBands) — categorical majority-class
	// =========================================================================

	@Test
	void esaBands_outputDimensionsCorrect() {
		EsaBands native_ = uniformEsaBands(200, 200, 10.0, EsaBandLayout.CODE_GRASSLAND);
		int[][] result = service.resampleEsa(native_);
		assertThat(result.length).isEqualTo(20);
		assertThat(result[0].length).isEqualTo(20);
	}

	@Test
	void esaBands_uniformSourcePreservesCode() {
		EsaBands native_ = uniformEsaBands(100, 100, 10.0, EsaBandLayout.CODE_SHRUBLAND);
		int[][] result = service.resampleEsa(native_);
		for (int r = 0; r < result.length; r++)
			for (int c = 0; c < result[r].length; c++)
				assertThat(result[r][c]).as("[%d][%d]", r, c)
					.isEqualTo(EsaBandLayout.CODE_SHRUBLAND);
	}

	@Test
	void esaBands_majorityCodeSelected() {
		// 3 pixels GRASSLAND (30), 1 pixel WATER (80) → majority is GRASSLAND
		int[][] codes = {
			{EsaBandLayout.CODE_GRASSLAND, EsaBandLayout.CODE_GRASSLAND},
			{EsaBandLayout.CODE_GRASSLAND, EsaBandLayout.CODE_PERMANENT_WATER}
		};
		int[][] result = serviceWithTarget(20.0).resampleEsa(esaBandsWithCodes(2, 2, 10.0, codes));
		assertThat(result[0][0]).isEqualTo(EsaBandLayout.CODE_GRASSLAND);
	}

	@Test
	void esaBands_tieBreakerPreferesCombustible() {
		// 2 pixels GRASSLAND (combustible), 2 pixels WATER (non-combustible) → tie → GRASSLAND wins
		int[][] codes = {
			{EsaBandLayout.CODE_GRASSLAND, EsaBandLayout.CODE_GRASSLAND},
			{EsaBandLayout.CODE_PERMANENT_WATER, EsaBandLayout.CODE_PERMANENT_WATER}
		};
		int[][] result = serviceWithTarget(20.0).resampleEsa(esaBandsWithCodes(2, 2, 10.0, codes));
		assertThat(result[0][0]).isEqualTo(EsaBandLayout.CODE_GRASSLAND);
	}

	@Test
	void esaBands_noOpWhenCellSizeMatches() {
		EsaBands native_ = uniformEsaBands(20, 20, 100.0, EsaBandLayout.CODE_CROPLAND);
		assertThat(service.resampleEsa(native_)).isSameAs(native_.getClassCode());
	}

	@Test
	void esaBands_upsamplingThrows() {
		EsaBands native_ = uniformEsaBands(100, 100, 10.0, EsaBandLayout.CODE_TREE_COVER);
		assertThatThrownBy(() -> serviceWithTarget(5.0).resampleEsa(native_))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void esaBands_partialEdgeBlockDoesNotThrow() {
		assertThatCode(() -> service.resampleEsa(uniformEsaBands(15, 15, 10.0, EsaBandLayout.CODE_GRASSLAND)))
			.doesNotThrowAnyException();
	}

	// =========================================================================
	// resampleFuelRisk(FuelRiskBands) — majority-class, higher-risk tie-break
	// =========================================================================

	@Test
	void fuelRisk_outputDimensionsCorrect() {
		FuelRiskBands native_ = uniformFuelRisk(200, 200, 10.0, (byte) 2);
		byte[][] result = service.resampleFuelRisk(native_);
		assertThat(result.length).isEqualTo(20);
		assertThat(result[0].length).isEqualTo(20);
	}

	@Test
	void fuelRisk_uniformSourcePreservesCode() {
		FuelRiskBands native_ = uniformFuelRisk(100, 100, 10.0, (byte) 3);
		byte[][] result = service.resampleFuelRisk(native_);
		for (int r = 0; r < result.length; r++)
			for (int c = 0; c < result[r].length; c++)
				assertThat(result[r][c]).as("[%d][%d]", r, c).isEqualTo((byte) 3);
	}

	@Test
	void fuelRisk_majorityCodeSelected() {
		// 3 pixels of risk=1, 1 pixel of risk=3 → majority is 1
		byte[][] codes = {
			{1, 1},
			{1, 3}
		};
		byte[][] result = serviceWithTarget(20.0).resampleFuelRisk(
			fuelRiskWithCodes(2, 2, 10.0, codes));
		assertThat(result[0][0]).isEqualTo((byte) 1);
	}

	@Test
	void fuelRisk_tieBreakerPrefersHigherRisk() {
		// 2 pixels of risk=1, 2 pixels of risk=3 → tie → higher risk (3) wins
		byte[][] codes = {
			{1, 1},
			{3, 3}
		};
		byte[][] result = serviceWithTarget(20.0).resampleFuelRisk(
			fuelRiskWithCodes(2, 2, 10.0, codes));
		assertThat(result[0][0]).isEqualTo((byte) 3);
	}

	@Test
	void fuelRisk_noDataExcludedFromVoting() {
		// 2 pixels of NoData (0), 2 pixels of risk=2 → NoData excluded → result is 2
		byte[][] codes = {
			{0, 0},
			{2, 2}
		};
		byte[][] result = serviceWithTarget(20.0).resampleFuelRisk(
			fuelRiskWithCodes(2, 2, 10.0, codes));
		assertThat(result[0][0]).isEqualTo((byte) 2);
	}

	@Test
	void fuelRisk_allNoDataBlockReturnsZero() {
		byte[][] codes = {
			{0, 0},
			{0, 0}
		};
		byte[][] result = serviceWithTarget(20.0).resampleFuelRisk(
			fuelRiskWithCodes(2, 2, 10.0, codes));
		assertThat(result[0][0]).isEqualTo((byte) 0);
	}

	@Test
	void fuelRisk_noOpWhenCellSizeMatches() {
		FuelRiskBands native_ = uniformFuelRisk(20, 20, 100.0, (byte) 2);
		assertThat(service.resampleFuelRisk(native_)).isSameAs(native_.getRiskCodes());
	}

	@Test
	void fuelRisk_upsamplingThrows() {
		FuelRiskBands native_ = uniformFuelRisk(100, 100, 10.0, (byte) 1);
		assertThatThrownBy(() -> serviceWithTarget(5.0).resampleFuelRisk(native_))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void fuelRisk_partialEdgeBlockDoesNotThrow() {
		assertThatCode(() -> service.resampleFuelRisk(uniformFuelRisk(15, 15, 10.0, (byte) 2)))
			.doesNotThrowAnyException();
	}

	// =========================================================================
	// Builders
	// =========================================================================

	private RasterResamplerService serviceWithTarget(double targetMetres) {
		SimulationConfig cfg = new SimulationConfig();
		cfg.setCellSizeMetres(targetMetres);
		return new RasterResamplerService(cfg);
	}

	private GridBands uniformGridBands(int rows, int cols, double cellSize, float value) {
		return new GridBands(
			filled(rows, cols, value), filled(rows, cols, value), filled(rows, cols, value),
			filled(rows, cols, value), filled(rows, cols, value), filled(rows, cols, value),
			rows, cols, cellSize, 0, 0, cols * cellSize, rows * cellSize);
	}

	private GridBands gridBandsWithNdvi(int rows, int cols, double cellSize, float[][] ndvi) {
		return new GridBands(ndvi,
			filled(rows, cols, 0.2f), filled(rows, cols, 0.08f), filled(rows, cols, 1000f),
			filled(rows, cols, 5f), filled(rows, cols, 0.5f),
			rows, cols, cellSize, 0, 0, cols * cellSize, rows * cellSize);
	}

	private EsaBands uniformEsaBands(int rows, int cols, double cellSize, int code) {
		int[][] codes = new int[rows][cols];
		for (int r = 0; r < rows; r++)
			for (int c = 0; c < cols; c++)
				codes[r][c] = code;
		return new EsaBands(codes, rows, cols, cellSize, 0, 0, cols * cellSize, rows * cellSize);
	}

	private EsaBands esaBandsWithCodes(int rows, int cols, double cellSize, int[][] codes) {
		return new EsaBands(codes, rows, cols, cellSize, 0, 0, cols * cellSize, rows * cellSize);
	}

	private float[][] filled(int rows, int cols, float value) {
		float[][] arr = new float[rows][cols];
		for (int r = 0; r < rows; r++)
			for (int c = 0; c < cols; c++)
				arr[r][c] = value;
		return arr;
	}

	private FuelRiskBands uniformFuelRisk(int rows, int cols, double cellSize, byte value) {
		byte[][] codes = new byte[rows][cols];
		for (int r = 0; r < rows; r++)
			for (int c = 0; c < cols; c++)
				codes[r][c] = value;
		return new FuelRiskBands(codes, rows, cols, cellSize, 0, 0, cols * cellSize, rows * cellSize);
	}

	private FuelRiskBands fuelRiskWithCodes(int rows, int cols, double cellSize, byte[][] codes) {
		return new FuelRiskBands(codes, rows, cols, cellSize, 0, 0, cols * cellSize, rows * cellSize);
	}

	private static org.assertj.core.data.Offset<Float> within(float d) {
		return org.assertj.core.data.Offset.offset(d);
	}
}