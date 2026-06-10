package com.victorkithinji.wrap.wrapca.util;

import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridCoverageFactory;
import org.geotools.gce.geotiff.GeoTiffWriter;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;

import javax.media.jai.RasterFactory;
import java.awt.image.DataBuffer;
import java.awt.image.WritableRaster;
import java.io.File;
import java.util.Random;

/**
 * Generates a synthetic 12-band GeoTIFF fixture matching the confirmed CV output contract.
 * <p>
 * Band order mirrors the CV contract exactly:
 * 0  Blue, 1 Green, 2 Red, 3 NIR, 4 SWIR,
 * 5  NDVI, 6 NDMI, 7 NDWI,
 * 8  FMC (Fuel Moisture Content),
 * 9  Elevation, 10 Slope, 11 Aspect
 * <p>
 * CRS: EPSG:4326 (WGS84 geographic, degrees) — matching the delivered CV GeoTIFF.
 * GeoTiffBandReaderService reprojects the bounding box to EPSG:32737 on read.
 * <p>
 * The bounding box uses WGS84 degrees centred on the Aberdare area:
 * approximately 0.10°S to 0.08°S, 36.55°E to 36.73°E
 * (roughly 20km × 20km at equatorial scale)
 * <p>
 * Resolution: 2000×2000 pixels representing ~10m/pixel native CV resolution.
 * <p>
 * Spatial content:
 * NDVI:      0.65 base (forest), SW patch 0.25 (grassland), NE patch 0.01 (water)
 * NDMI:      correlated with NDVI (ndvi * 0.7 + noise)
 * FMC:       0.08–0.18 range (8–18% fuel moisture), higher where NDVI is higher
 * Elevation: 1800m–2200m W→E gradient
 * Slope:     0–15 degrees with noise
 * Aspect:    uniform westerly (~4.71 rad = 270°)
 * <p>
 * Run once to produce data/geotiff/latest_cv_output.tif for local development.
 * This file is not committed to version control.
 */
public class SyntheticGeoTiffGenerator {

	public static final int ROWS = 2000;
	public static final int COLS = 2000;

	// Aberdare area bounding box in WGS84 degrees
	// minLon, maxLon, minLat, maxLat
	private static final double MIN_LON = 36.55;
	private static final double MAX_LON = 36.73;
	private static final double MIN_LAT = -0.10;
	private static final double MAX_LAT = 0.08;

	private static final int BANDS = 12;

	// Band indices matching BandLayout constants
	private static final int B_BLUE = 0;
	private static final int B_GREEN = 1;
	private static final int B_RED = 2;
	private static final int B_NIR = 3;
	private static final int B_SWIR = 4;
	private static final int B_NDVI = 5;
	private static final int B_NDMI = 6;
	private static final int B_NDWI = 7;
	private static final int B_FMC = 8;
	private static final int B_ELEVATION = 9;
	private static final int B_SLOPE = 10;
	private static final int B_ASPECT = 11;

	public static void main(String[] args) throws Exception {
		String outputPath = args.length > 0 ? args[0] : "data/geotiff/latest_cv_output.tif";
		generate(new File(outputPath));
		System.out.println("Synthetic GeoTIFF written to: " + outputPath);
	}

	public static void generate(File outputFile) throws Exception {
		outputFile.getParentFile().mkdirs();

		WritableRaster raster = RasterFactory.createBandedRaster(
			DataBuffer.TYPE_FLOAT, COLS, ROWS, BANDS, null);

		Random rng = new Random(42);

		for (int row = 0; row < ROWS; row++) {
			for (int col = 0; col < COLS; col++) {
				float ndvi = computeNdvi(row, col, rng);
				float ndmi = Math.max(0f, ndvi * 0.7f + (rng.nextFloat() - 0.5f) * 0.05f);
				float ndwi = isWaterPatch(row, col) ? 0.4f : -0.2f;
				// FMC: direct moisture fraction. Higher vegetation = higher moisture.
				// Range 0.08 (dry grass) to 0.18 (moist forest), with noise.
				float fmc = 0.08f + ndvi * 0.15f + (rng.nextFloat() - 0.5f) * 0.01f;
				fmc = Math.max(0.03f, Math.min(0.40f, fmc));
				float elevation = 1800f + (col / (float) COLS) * 400f;
				float slope = Math.max(0f, 1.15f + (rng.nextFloat() - 0.5f) * 2.0f);
				float aspect = (float) (Math.PI * 1.5); // 270° westerly

				float blue = 0.04f + rng.nextFloat() * 0.02f;
				float green = 0.06f + rng.nextFloat() * 0.02f;
				float red = isWaterPatch(row, col) ? 0.02f : 0.05f + rng.nextFloat() * 0.03f;
				float nir = Math.max(0f, ndvi * (red + 0.01f) + red);
				float swir = Math.max(0f, (1f - ndmi) * 0.3f);

				raster.setSample(col, row, B_BLUE, blue);
				raster.setSample(col, row, B_GREEN, green);
				raster.setSample(col, row, B_RED, red);
				raster.setSample(col, row, B_NIR, nir);
				raster.setSample(col, row, B_SWIR, swir);
				raster.setSample(col, row, B_NDVI, ndvi);
				raster.setSample(col, row, B_NDMI, ndmi);
				raster.setSample(col, row, B_NDWI, ndwi);
				raster.setSample(col, row, B_FMC, fmc);
				raster.setSample(col, row, B_ELEVATION, elevation);
				raster.setSample(col, row, B_SLOPE, slope);
				raster.setSample(col, row, B_ASPECT, aspect);
			}
		}

		// GeoTIFF envelope in EPSG:4326 (degrees), matching the delivered CV GeoTIFF CRS
		CoordinateReferenceSystem wgs84 = CRS.decode("EPSG:4326", true);
		ReferencedEnvelope envelope = new ReferencedEnvelope(
			MIN_LON, MAX_LON, MIN_LAT, MAX_LAT, wgs84);

		GridCoverageFactory factory = new GridCoverageFactory();
		GridCoverage2D coverage = factory.create("synthetic_fuel_state", raster, envelope);

		GeoTiffWriter writer = new GeoTiffWriter(outputFile);
		writer.write(coverage, null);
		writer.dispose();
	}

	private static float computeNdvi(int row, int col, Random rng) {
		float noise = (rng.nextFloat() - 0.5f) * 0.04f;
		if (isWaterPatch(row, col)) return 0.01f + noise * 0.1f;
		if (isGrasslandPatch(row, col)) return 0.25f + noise;
		return 0.65f + noise;
	}

	private static boolean isWaterPatch(int row, int col) {
		return row < 200 && col >= 1800;
	}

	private static boolean isGrasslandPatch(int row, int col) {
		return row >= 1200 && row < 1600 && col >= 200 && col < 600;
	}
}