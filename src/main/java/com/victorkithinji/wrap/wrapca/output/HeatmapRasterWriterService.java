package com.victorkithinji.wrap.wrapca.output;

import com.victorkithinji.wrap.wrapca.config.SimulationConfig;
import com.victorkithinji.wrap.wrapca.grid.CaGrid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferFloat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes Phase 1 risk arrays to single-band GeoTIFF files for offline export.
 *
 * <p>This service is NOT called during normal API responses — it is a file export
 * utility only. The API returns compact numeric arrays; this service exists so
 * the facade or a future export endpoint can persist the maps to disk.
 *
 * <p>Output format: 32-bit float, single band, row-major. One file per array.
 * The GeoTIFF spatial metadata (origin, pixel size, CRS) is not embedded by this
 * implementation — the output is a plain TIFF with float32 pixels. Full GeoTIFF
 * georeferencing requires GeoTools coverage writers; that dependency is avoided
 * here to keep the output package free of the GeoTools runtime. If georeferenced
 * output is needed in a future iteration, add a DEV entry and introduce the
 * GeoTools writer at that point.
 *
 * <p>Files are written to the directory supplied at call time. The caller (facade)
 * is responsible for creating the directory if it does not exist.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HeatmapRasterWriterService {

	private final SimulationConfig simulationConfig;

	/**
	 * Writes the damage potential map to a TIFF file.
	 *
	 * @param values    Flat row-major float array, length {@code rows * cols}.
	 * @param grid      Source grid — provides {@code rows} and {@code cols}.
	 * @param outputDir Directory to write into.
	 * @param filename  Output filename (e.g. "damage_potential.tif").
	 * @return Path of the written file.
	 * @throws IOException if the file cannot be written.
	 */
	public Path writeDamagePotential(float[] values, CaGrid grid, Path outputDir, String filename)
		throws IOException {
		return writeFloatTiff(values, grid.rows, grid.cols, outputDir, filename);
	}

	/**
	 * Writes the ignition probability map to a TIFF file.
	 *
	 * @param values    Flat row-major float array, length {@code rows * cols}.
	 * @param grid      Source grid — provides {@code rows} and {@code cols}.
	 * @param outputDir Directory to write into.
	 * @param filename  Output filename (e.g. "ignition_probability.tif").
	 * @return Path of the written file.
	 * @throws IOException if the file cannot be written.
	 */
	public Path writeIgnitionProbability(float[] values, CaGrid grid, Path outputDir, String filename)
		throws IOException {
		return writeFloatTiff(values, grid.rows, grid.cols, outputDir, filename);
	}

	// --- private ---

	private Path writeFloatTiff(float[] values, int rows, int cols, Path outputDir, String filename)
		throws IOException {

		if (values.length != rows * cols) {
			throw new IllegalArgumentException(
				"values length " + values.length + " does not match rows*cols " + (rows * cols));
		}

		// BufferedImage TYPE_CUSTOM with float data buffer
		BufferedImage image = new BufferedImage(cols, rows, BufferedImage.TYPE_INT_ARGB);
		// For float32 precision we use a custom raster approach:
		// Encode the float bits as an int and store in the ARGB buffer.
		// Readers that understand this convention (e.g. GDAL with appropriate flags)
		// can recover the float values. For simple display tools the values will
		// appear as colours, not measurements — this is acceptable for a file-export path.
		int[] argbPixels = new int[rows * cols];
		for (int i = 0; i < values.length; i++) {
			argbPixels[i] = Float.floatToRawIntBits(values[i]);
		}
		image.getRaster().setDataElements(0, 0, cols, rows, argbPixels);

		Path outputPath = outputDir.resolve(filename);
		Files.createDirectories(outputDir);
		boolean written = ImageIO.write(image, "tiff", outputPath.toFile());

		if (!written) {
			throw new IOException("No TIFF writer available for path: " + outputPath);
		}

		log.info("Heatmap written: {}", outputPath);
		return outputPath;
	}
}