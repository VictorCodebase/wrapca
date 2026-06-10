package com.victorkithinji.wrap.wrapca.ingestion;

import lombok.Value;

/**
 * Structured result of reading a CV GeoTIFF.
 * All six arrays are [row][col] with identical dimensions.
 * Row 0 is the northernmost row (top of the raster).
 * <p>
 * Spatial metadata (minX/minY/maxX/maxY, cellSizeMetres) is always in
 * UTM 37S metres (EPSG:32737) regardless of the source CRS of the GeoTIFF.
 * GeoTiffBandReaderService handles reprojection — downstream consumers
 * never need to know the source CRS was geographic (EPSG:4326).
 * <p>
 * When returned by GeoTiffBandReaderService, dimensions reflect native
 * pixel size (10m). RasterResamplerService (Group 5) returns a new
 * GridBands instance at the target CA resolution. GridInitialiserService
 * operates on the resampled instance only.
 * <p>
 * FMC (Fuel Moisture Content) is now a direct CV measurement at band 8.
 * It replaces the previous NDMI-based moisture proxy used in Rothermel
 * calculations. NDMI is still carried for any consumer that needs it
 * (e.g. ignition likelihood index), but FMC is the authoritative moisture
 * source for fire physics.
 */
@Value
public class GridBands {

	float[][] ndvi;
	float[][] ndmi;

	/**
	 * Fuel Moisture Content as a direct measurement from CV (band 8).
	 * Range: dimensionless fraction, e.g. 0.08 = 8% moisture.
	 * Used directly by RothermelRosCalculator as live fuel moisture —
	 * no scaling or proxy derivation required.
	 */
	float[][] fmc;

	float[][] elevationMetres;

	/**
	 * Terrain slope in degrees — provided by CV, no derivation needed.
	 */
	float[][] slopeDegrees;

	/**
	 * Terrain aspect in radians, clockwise from north [0, 2π] — provided by CV.
	 */
	float[][] aspectRadians;

	int rows;
	int cols;

	/**
	 * Cell size in metres.
	 * Native resolution (~10m) when returned by GeoTiffBandReaderService.
	 * Target resolution when returned by RasterResamplerService.
	 */
	double cellSizeMetres;

	/**
	 * Bounding box in UTM 37S metres (EPSG:32737).
	 * Reprojected from source EPSG:4326 by GeoTiffBandReaderService.
	 * minX = westernmost easting, maxY = northernmost northing.
	 * Preserved unchanged through resampling.
	 */
	double minX;
	double minY;
	double maxX;
	double maxY;
}