package com.victorkithinji.wrap.wrapca.ingestion;

import lombok.Value;

/**
 * Structured result of reading the CV fuel risk map GeoTIFF.
 * Produced by GeoTiffBandReaderService.readFuelRisk() and consumed by
 * RasterResamplerService (Group 5).
 * <p>
 * The fuel risk map is a single-band byte raster where each pixel carries
 * a risk category:
 * 1 — low risk
 * 2 — medium risk
 * 3 — high risk
 * 0 — NoData (treat as transparent / lowest risk for display)
 * <p>
 * This file is a static product from CV representing the pre-computed
 * fuel risk assessment. It is not produced by the simulation engine and
 * does not change between runs. It is display-only — the simulation engine
 * never reads these values.
 * <p>
 * Spatial metadata is in EPSG:4326 degrees as delivered by CV.
 * RasterResamplerService aligns it to the CA grid during Group 5 processing.
 */
@Value
public class FuelRiskBands {
	byte[][] riskCodes;
	int rows;
	int cols;
	double cellSizeMetres;
	double minX;
	double minY;
	double maxX;
	double maxY;
}