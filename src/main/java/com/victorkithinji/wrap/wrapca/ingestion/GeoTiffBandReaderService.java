package com.victorkithinji.wrap.wrapca.ingestion;

import lombok.extern.slf4j.Slf4j;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.io.GridCoverage2DReader;
import org.geotools.gce.geotiff.GeoTiffFormat;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.awt.image.Raster;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Reads the CV GeoTIFF and extracts the bands the CA engine needs.
 * <p>
 * Returns GridBands at native resolution (~10m). Downsampling to the CA
 * engine's target resolution is RasterResamplerService's responsibility.
 * <p>
 * CRS handling:
 * The CV GeoTIFF is delivered in EPSG:4326 (WGS84 geographic, degrees).
 * All downstream consumers expect spatial metadata in EPSG:32737
 * (WGS84 / UTM Zone 37S, metres). This service reprojects the bounding
 * box envelope before returning GridBands. The pixel arrays themselves
 * are not reprojected — only the envelope coordinates change.
 * Downstream groups are completely abstracted from this detail.
 * <p>
 * Band selection is defined in BandLayout. That is the only file to update
 * if CV changes its band layout.
 * <p>
 * Windows note: coverage.dispose(true) before reader.dispose() releases
 * the ImageIO file handle in the correct order on Windows.
 */
@Slf4j
@Service
public class GeoTiffBandReaderService {

	public GeoTiffBandReaderService(
		@Value("${wrap.simulation.cell-size-metres}") double ignoredTargetSize) {
		// Target cell size is not used here — resampling is Group 5's concern.
		// Parameter retained to avoid breaking Spring injection if wired.
	}

	/**
	 * Reads the CV GeoTIFF and returns native-resolution band arrays with
	 * a UTM 37S bounding box regardless of the file's source CRS.
	 *
	 * @param tiffPath path to the CV GeoTIFF
	 * @return GridBands at native resolution with UTM 37S spatial metadata
	 * @throws IOException if the file cannot be read
	 */
	public GridBands read(Path tiffPath) throws IOException {
		File file = tiffPath.toFile();
		if (!file.exists()) {
			throw new IOException("GeoTIFF not found: " + tiffPath);
		}

		System.out.println("Opening geotiff file: " + tiffPath);

		org.geotools.util.factory.Hints hints = new org.geotools.util.factory.Hints(
			org.geotools.util.factory.Hints.FORCE_LONGITUDE_FIRST_AXIS_ORDER,
			Boolean.TRUE
		);
		GeoTiffFormat format = new GeoTiffFormat();
		GridCoverage2DReader reader = format.getReader(file, hints);
		if (reader == null) {
			throw new IOException("File is not a valid GeoTIFF: " + tiffPath);
		}

		GridCoverage2D coverage = null;
		try {
			coverage = reader.read(null);
			validateBandCount(coverage, tiffPath);

			// Debug logs:
			log.info("GeoTIFF image: {}", tiffPath);
			CoordinateReferenceSystem nativeCrs = coverage.getCoordinateReferenceSystem2D();
			ReferencedEnvelope nativeEnv = coverage.getEnvelope2D();
			log.info("GeoTIFF native CRS: {}", nativeCrs.getName().getCode());
			log.info("GeoTIFF native envelope: {}", nativeEnv);

			// Now call your existing method and log its result
			ReferencedEnvelope utmEnv = toUtmEnvelope(coverage);
			log.info("toUtmEnvelope result: {}", utmEnv);
			log.info("toUtmEnvelope CRS: {}", utmEnv.getCoordinateReferenceSystem().getName().getCode());

			Raster raster = coverage.getRenderedImage().getData();
			int cols = raster.getWidth();
			int rows = raster.getHeight();

			log.info("Reading CV GeoTIFF: {}x{} cells from {}", rows, cols, tiffPath);

			float[][] ndvi = extractFloatBand(raster, rows, cols, BandLayout.NDVI_BAND);
			float[][] ndmi = extractFloatBand(raster, rows, cols, BandLayout.NDMI_BAND);
			float[][] fmc = extractFloatBand(raster, rows, cols, BandLayout.FMC_BAND);
			float[][] elevation = extractFloatBand(raster, rows, cols, BandLayout.ELEVATION_BAND);
			float[][] slope = extractFloatBand(raster, rows, cols, BandLayout.SLOPE_BAND);
			float[][] aspect = extractFloatBand(raster, rows, cols, BandLayout.ASPECT_BAND);

			ReferencedEnvelope utmEnvelope = toUtmEnvelope(coverage);
			double pixelWidth = utmEnvelope.getWidth() / cols;

			if (Math.abs(pixelWidth - BandLayout.EXPECTED_NATIVE_PIXEL_SIZE) > 1.0) {
				log.warn("Derived pixel size {}m differs from expected {}m. " +
						"RasterResamplerService will resample to the configured target.",
					String.format("%.2f", pixelWidth),
					BandLayout.EXPECTED_NATIVE_PIXEL_SIZE);
			}

			return new GridBands(
				ndvi, ndmi, fmc, elevation, slope, aspect,
				rows, cols, pixelWidth,
				utmEnvelope.getMinX(), utmEnvelope.getMinY(),
				utmEnvelope.getMaxX(), utmEnvelope.getMaxY()
			);

		} finally {
			if (coverage != null) coverage.dispose(true);
			reader.dispose();
		}
	}

	/**
	 * Reads the ESA WorldCover GeoTIFF and returns native-resolution class codes.
	 * The ESA file is also expected in EPSG:4326 — bounding box is reprojected
	 * to UTM 37S using the same approach as the CV GeoTIFF reader.
	 *
	 * @param esaTiffPath path to the ESA WorldCover GeoTIFF
	 * @return EsaBands at native resolution with UTM 37S spatial metadata
	 * @throws IOException if the file cannot be read
	 */
	public EsaBands readEsa(Path esaTiffPath) throws IOException {
		File file = esaTiffPath.toFile();
		if (!file.exists()) {
			throw new IOException("ESA WorldCover GeoTIFF not found: " + esaTiffPath);
		}
		org.geotools.util.factory.Hints hints = new org.geotools.util.factory.Hints(
			org.geotools.util.factory.Hints.FORCE_LONGITUDE_FIRST_AXIS_ORDER,
			Boolean.TRUE
		);

		GeoTiffFormat format = new GeoTiffFormat();
		GridCoverage2DReader reader = format.getReader(file, hints);
		if (reader == null) {
			throw new IOException("File is not a valid GeoTIFF: " + esaTiffPath);
		}

		GridCoverage2D coverage = null;
		try {
			coverage = reader.read(null);

			if (coverage.getNumSampleDimensions() < 1) {
				throw new IllegalArgumentException(
					"ESA GeoTIFF at " + esaTiffPath + " has no bands.");
			}

			Raster raster = coverage.getRenderedImage().getData();
			int cols = raster.getWidth();
			int rows = raster.getHeight();

			log.info("Reading ESA WorldCover GeoTIFF: {}x{} cells from {}", rows, cols, esaTiffPath);

			int[][] classCodes = extractIntBand(raster, rows, cols);
			ReferencedEnvelope utmEnvelope = toUtmEnvelope(coverage);
			double pixelWidth = utmEnvelope.getWidth() / cols;

			return new EsaBands(
				classCodes, rows, cols, pixelWidth,
				utmEnvelope.getMinX(), utmEnvelope.getMinY(),
				utmEnvelope.getMaxX(), utmEnvelope.getMaxY()
			);

		} finally {
			if (coverage != null) coverage.dispose(true);
			reader.dispose();
		}
	}

	/**
	 * Reads the CV fuel risk map GeoTIFF and returns native-resolution risk codes.
	 * <p>
	 * Single-band byte raster. Values: 1 (low), 2 (medium), 3 (high), 0 (NoData).
	 * Bounding box is returned in EPSG:4326 degrees — RasterResamplerService
	 * handles alignment to the CA grid extent during resampling.
	 * <p>
	 * If the file is missing the facade passes a zero-filled array instead —
	 * this method must only be called when the file is confirmed to exist.
	 *
	 * @param fuelRiskPath path to the fuel risk GeoTIFF
	 * @return FuelRiskBands at native resolution
	 * @throws IOException if the file cannot be read
	 */
	public FuelRiskBands readFuelRisk(Path fuelRiskPath) throws IOException {
		File file = fuelRiskPath.toFile();
		if (!file.exists()) {
			throw new IOException("Fuel risk GeoTIFF not found: " + fuelRiskPath);
		}

		org.geotools.util.factory.Hints hints = new org.geotools.util.factory.Hints(
			org.geotools.util.factory.Hints.FORCE_LONGITUDE_FIRST_AXIS_ORDER,
			Boolean.TRUE
		);
		GeoTiffFormat format = new GeoTiffFormat();
		GridCoverage2DReader reader = format.getReader(file, hints);
		if (reader == null) {
			throw new IOException("File is not a valid GeoTIFF: " + fuelRiskPath);
		}

		GridCoverage2D coverage = null;
		try {
			coverage = reader.read(null);

			int actualBands = coverage.getNumSampleDimensions();
			if (actualBands < 1) {
				throw new IllegalArgumentException(
					"Fuel risk GeoTIFF at " + fuelRiskPath + " has no bands.");
			}
			if (actualBands != 1) {
				log.warn("Fuel risk GeoTIFF has {} bands, expected 1. " +
					"Only band 0 will be read.", actualBands);
			}

			Raster raster = coverage.getRenderedImage().getData();
			int cols = raster.getWidth();
			int rows = raster.getHeight();

			log.info("Reading fuel risk GeoTIFF: {}x{} cells from {}", rows, cols, fuelRiskPath);

			byte[][] riskCodes = extractByteBand(raster, rows, cols);

			// Reproject the envelope to the target CRS (UTM 37S in meters)
			// RasterResamplerService aligns it to the CV grid extent.
//			ReferencedEnvelope envelope =
//				ReferencedEnvelope.reference(coverage.getEnvelope2D());
//			double pixelWidth = envelope.getWidth() / cols;

			ReferencedEnvelope utmEnvelope = toUtmEnvelope(coverage);
			double pixelWidth = utmEnvelope.getWidth() / cols;

			return new FuelRiskBands(
				riskCodes, rows, cols, pixelWidth,
				utmEnvelope.getMinX(), utmEnvelope.getMinY(),
				utmEnvelope.getMaxX(), utmEnvelope.getMaxY()
			);

		} finally {
			if (coverage != null) coverage.dispose(true);
			reader.dispose();
		}
	}

	// ─── CRS reprojection ─────────────────────────────────────────────────────

	/**
	 * Reprojects the coverage envelope to UTM 37S (EPSG:32737).
	 * <p>
	 * If the file is already in EPSG:32737 the transform is a no-op and the
	 * original envelope is returned unchanged. This means the reader is safe
	 * to use even if CV corrects the CRS in a future delivery.
	 */
	private ReferencedEnvelope toUtmEnvelope(GridCoverage2D coverage) throws IOException {
		try {
			CoordinateReferenceSystem sourceCrs =
				coverage.getCoordinateReferenceSystem2D();
			CoordinateReferenceSystem targetCrs =
				CRS.decode(BandLayout.TARGET_CRS_CODE, true);

			ReferencedEnvelope sourceEnvelope =
				ReferencedEnvelope.reference(coverage.getEnvelope2D());


			if (CRS.equalsIgnoreMetadata(sourceCrs, targetCrs)) {
				log.debug("Coverage CRS is already {}. No reprojection needed.",
					BandLayout.TARGET_CRS_CODE);
				return sourceEnvelope;
			}

			log.info("Reprojecting envelope from {} to {}.",
				BandLayout.SOURCE_CRS_CODE, BandLayout.TARGET_CRS_CODE);

			ReferencedEnvelope utmEnvelope = sourceEnvelope.transform(targetCrs, true);
			log.debug("Reprojected envelope: minX={}, minY={}, maxX={}, maxY={}",
				String.format("%.1f", utmEnvelope.getMinX()),
				String.format("%.1f", utmEnvelope.getMinY()),
				String.format("%.1f", utmEnvelope.getMaxX()),
				String.format("%.1f", utmEnvelope.getMaxY()));

			return utmEnvelope;

		} catch (Exception e) {
			throw new IOException("Failed to reproject envelope to " +
				BandLayout.TARGET_CRS_CODE + ": " + e.getMessage(), e);
		}
	}

	// ─── Band extraction ──────────────────────────────────────────────────────

	private float[][] extractFloatBand(Raster raster, int rows, int cols, int bandIndex) {
		float[][] result = new float[rows][cols];
		float[] pixel = new float[raster.getNumBands()];
		for (int row = 0; row < rows; row++) {
			for (int col = 0; col < cols; col++) {
				raster.getPixel(raster.getMinX() + col, raster.getMinY() + row, pixel);
				result[row][col] = pixel[bandIndex];
			}
		}
		return result;
	}

	private int[][] extractIntBand(Raster raster, int rows, int cols) {
		int[][] result = new int[rows][cols];
		int[] pixel = new int[raster.getNumBands()];
		for (int row = 0; row < rows; row++) {
			for (int col = 0; col < cols; col++) {
				raster.getPixel(raster.getMinX() + col, raster.getMinY() + row, pixel);
				result[row][col] = pixel[0];
			}
		}
		return result;
	}

	private byte[][] extractByteBand(Raster raster, int rows, int cols) {
		byte[][] result = new byte[rows][cols];
		int[] pixel = new int[raster.getNumBands()];
		for (int row = 0; row < rows; row++) {
			for (int col = 0; col < cols; col++) {
				raster.getPixel(raster.getMinX() + col, raster.getMinY() + row, pixel);
				// Clamp to byte range — raster stores unsigned values as int
				result[row][col] = (byte) (pixel[0] & 0xFF);
			}
		}
		return result;
	}


	// ─── Validation ───────────────────────────────────────────────────────────

	private void validateBandCount(GridCoverage2D coverage, Path tiffPath) {
		int actualBands = coverage.getNumSampleDimensions();
		if (actualBands <= BandLayout.MAX_REQUIRED_BAND_INDEX) {
			throw new IllegalArgumentException(String.format(
				"GeoTIFF at %s has %d bands but band index %d (ASPECT) is required. " +
					"Expected %d bands total. Check BandLayout against CV output.",
				tiffPath, actualBands,
				BandLayout.MAX_REQUIRED_BAND_INDEX, BandLayout.EXPECTED_BAND_COUNT));
		}
		if (actualBands != BandLayout.EXPECTED_BAND_COUNT) {
			log.warn("GeoTIFF has {} bands, expected {}. Proceeding — required bands are present.",
				actualBands, BandLayout.EXPECTED_BAND_COUNT);
		}
	}
}