package com.victorkithinji.wrap.wrapca.output;

import com.victorkithinji.wrap.wrapca.config.SimulationConfig;
import com.victorkithinji.wrap.wrapca.grid.CaGrid;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link HeatmapRasterWriterService}.
 * <p>
 * Tests verify:
 * - Files are created at the expected path
 * - Output directory is created if it does not exist
 * - Written TIFF is a readable image with correct dimensions
 * - Float values are encoded as raw int bits (and can be recovered)
 * - IllegalArgumentException for mismatched array lengths
 * - Both writeDamagePotential and writeIgnitionProbability delegate to the same logic
 * - Filenames are used as-is
 * - Nested directories are created
 */
@DisplayName("HeatmapRasterWriterService")
@ExtendWith(MockitoExtension.class)
class HeatmapRasterWriterServiceTest {

	@Mock
	private SimulationConfig simulationConfig;

	private HeatmapRasterWriterService writer;

	@TempDir
	Path tempDir;

	@BeforeEach
	void setUp() {
		writer = new HeatmapRasterWriterService(simulationConfig);
	}

	// -------------------------------------------------------------------------
	// File creation
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("file creation")
	class FileCreation {

		@Test
		@DisplayName("writeDamagePotential creates a file at outputDir/filename")
		void damagePotential_fileCreated() throws IOException {
			CaGrid grid = GridTestFactory.allUnburned(2, 3);
			float[] values = new float[6];

			Path result = writer.writeDamagePotential(values, grid, tempDir, "damage.tif");

			assertThat(result).exists();
			assertThat(result.getFileName().toString()).isEqualTo("damage.tif");
		}

		@Test
		@DisplayName("writeIgnitionProbability creates a file at outputDir/filename")
		void ignitionProbability_fileCreated() throws IOException {
			CaGrid grid = GridTestFactory.allUnburned(2, 3);
			float[] values = new float[6];

			Path result = writer.writeIgnitionProbability(values, grid, tempDir, "ignition.tif");

			assertThat(result).exists();
			assertThat(result.getFileName().toString()).isEqualTo("ignition.tif");
		}

		@Test
		@DisplayName("returned path is inside the output directory")
		void returnedPath_isInsideOutputDir() throws IOException {
			CaGrid grid = GridTestFactory.allUnburned(2, 2);
			float[] values = new float[4];

			Path result = writer.writeDamagePotential(values, grid, tempDir, "out.tif");

			assertThat(result.getParent()).isEqualTo(tempDir);
		}

		@Test
		@DisplayName("output directory is created if it does not exist")
		void missingOutputDir_isCreated() throws IOException {
			Path newDir = tempDir.resolve("nested/output/dir");
			assertThat(newDir).doesNotExist();

			CaGrid grid = GridTestFactory.allUnburned(1, 1);
			float[] values = new float[1];

			writer.writeDamagePotential(values, grid, newDir, "test.tif");

			assertThat(newDir).exists().isDirectory();
		}

		@Test
		@DisplayName("deeply nested output directory is created")
		void deeplyNested_dirCreated() throws IOException {
			Path deep = tempDir.resolve("a/b/c/d");
			CaGrid grid = GridTestFactory.allUnburned(1, 1);
			float[] values = new float[1];

			Path result = writer.writeIgnitionProbability(values, grid, deep, "deep.tif");

			assertThat(result).exists();
		}

		@Test
		@DisplayName("custom filename is used verbatim")
		void customFilename_usedVerbatim() throws IOException {
			CaGrid grid = GridTestFactory.allUnburned(2, 2);
			float[] values = new float[4];
			String name = "my_special_damage_2025-06-15.tif";

			Path result = writer.writeDamagePotential(values, grid, tempDir, name);

			assertThat(result.getFileName().toString()).isEqualTo(name);
		}
	}

	// -------------------------------------------------------------------------
	// Image dimensions
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("image dimensions")
	class ImageDimensions {

		@Test
		@DisplayName("written TIFF has correct width (cols) and height (rows)")
		void tiff_correctDimensions() throws IOException {
			int rows = 4;
			int cols = 7;
			CaGrid grid = GridTestFactory.allUnburned(rows, cols);
			float[] values = new float[rows * cols];

			Path result = writer.writeDamagePotential(values, grid, tempDir, "dim_test.tif");

			BufferedImage image = ImageIO.read(result.toFile());
			assertThat(image).isNotNull();
			assertThat(image.getWidth()).isEqualTo(cols);
			assertThat(image.getHeight()).isEqualTo(rows);
		}

		@Test
		@DisplayName("1x1 grid produces 1x1 TIFF")
		void oneByOne_singlePixelTiff() throws IOException {
			CaGrid grid = GridTestFactory.allUnburned(1, 1);
			float[] values = {0.5f};

			Path result = writer.writeDamagePotential(values, grid, tempDir, "tiny.tif");

			BufferedImage image = ImageIO.read(result.toFile());
			assertThat(image.getWidth()).isEqualTo(1);
			assertThat(image.getHeight()).isEqualTo(1);
		}

		@Test
		@DisplayName("10x10 grid produces 10x10 TIFF")
		void tenByTen_correctDimensions() throws IOException {
			CaGrid grid = GridTestFactory.allUnburned(10, 10);
			float[] values = new float[100];

			Path result = writer.writeIgnitionProbability(values, grid, tempDir, "ten.tif");

			BufferedImage image = ImageIO.read(result.toFile());
			assertThat(image.getWidth()).isEqualTo(10);
			assertThat(image.getHeight()).isEqualTo(10);
		}
	}

	// -------------------------------------------------------------------------
	// Float encoding — raw bit preservation
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("float value encoding")
	class FloatEncoding {

		@Test
		@DisplayName("float values are encoded as raw int bits and can be recovered")
		void floatValues_recoverable() throws IOException {
			CaGrid grid = GridTestFactory.allUnburned(1, 4);
			float[] original = {0.0f, 0.25f, 0.5f, 1.0f};

			Path result = writer.writeDamagePotential(original, grid, tempDir, "encoded.tif");

			BufferedImage image = ImageIO.read(result.toFile());
			assertThat(image).isNotNull();

			// TYPE_INT_ARGB stores one int per pixel. getRGB(x, y) returns that int
			// directly — getDataElements would return a byte[] internally and cause
			// a ClassCastException when assigned to int[].
			for (int i = 0; i < original.length; i++) {
				int rawBits = image.getRGB(i, 0);
				float recovered = Float.intBitsToFloat(rawBits);
				assertThat(recovered)
					.as("pixel %d should recover float %f", i, original[i])
					.isEqualTo(original[i]);
			}
		}

		@Test
		@DisplayName("all-zero values produce a readable TIFF")
		void allZeroValues_readableTiff() throws IOException {
			CaGrid grid = GridTestFactory.allUnburned(3, 3);
			float[] values = new float[9]; // all zero

			Path result = writer.writeDamagePotential(values, grid, tempDir, "zeros.tif");

			assertThat(ImageIO.read(result.toFile())).isNotNull();
		}

		@Test
		@DisplayName("all-one values produce a readable TIFF")
		void allOneValues_readableTiff() throws IOException {
			CaGrid grid = GridTestFactory.allUnburned(3, 3);
			float[] values = new float[9];
			java.util.Arrays.fill(values, 1.0f);

			Path result = writer.writeIgnitionProbability(values, grid, tempDir, "ones.tif");

			assertThat(ImageIO.read(result.toFile())).isNotNull();
		}

		@Test
		@DisplayName("NaN value is encoded without throwing")
		void nanValue_encodedWithoutThrowing() throws IOException {
			CaGrid grid = GridTestFactory.allUnburned(1, 1);
			float[] values = {Float.NaN};

			assertThatCode(() -> writer.writeDamagePotential(values, grid, tempDir, "nan.tif"))
				.doesNotThrowAnyException();
		}
	}

	// -------------------------------------------------------------------------
	// Array length validation
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("array length validation")
	class ArrayLengthValidation {

		@Test
		@DisplayName("array length less than rows*cols throws IllegalArgumentException")
		void shortArray_throwsIllegalArgument() {
			CaGrid grid = GridTestFactory.allUnburned(3, 3); // expects 9
			float[] values = new float[8];

			assertThatThrownBy(() -> writer.writeDamagePotential(values, grid, tempDir, "bad.tif"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("8")
				.hasMessageContaining("9");
		}

		@Test
		@DisplayName("array length greater than rows*cols throws IllegalArgumentException")
		void longArray_throwsIllegalArgument() {
			CaGrid grid = GridTestFactory.allUnburned(2, 2); // expects 4
			float[] values = new float[5];

			assertThatThrownBy(() -> writer.writeIgnitionProbability(values, grid, tempDir, "bad.tif"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("5")
				.hasMessageContaining("4");
		}

		@Test
		@DisplayName("empty array with 1x1 grid throws IllegalArgumentException")
		void emptyArray_oneByOne_throws() {
			CaGrid grid = GridTestFactory.allUnburned(1, 1);
			float[] values = new float[0];

			assertThatThrownBy(() -> writer.writeDamagePotential(values, grid, tempDir, "bad.tif"))
				.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("correct array length does not throw")
		void correctLength_doesNotThrow() {
			CaGrid grid = GridTestFactory.allUnburned(3, 4); // 12 cells
			float[] values = new float[12];

			assertThatCode(() -> writer.writeDamagePotential(values, grid, tempDir, "ok.tif"))
				.doesNotThrowAnyException();
		}
	}

	// -------------------------------------------------------------------------
	// Both public methods delegate the same way
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("method symmetry")
	class MethodSymmetry {

		@Test
		@DisplayName("writeDamagePotential and writeIgnitionProbability produce same-sized files")
		void bothMethods_produceSameSizedFiles() throws IOException {
			CaGrid grid = GridTestFactory.allUnburned(5, 5);
			float[] values = new float[25];

			Path dp = writer.writeDamagePotential(values, grid, tempDir, "dp.tif");
			Path ip = writer.writeIgnitionProbability(values, grid, tempDir, "ip.tif");

			assertThat(Files.size(dp)).isEqualTo(Files.size(ip));
		}

		@Test
		@DisplayName("both methods produce readable TIFF images")
		void bothMethods_produceReadableTiffs() throws IOException {
			CaGrid grid = GridTestFactory.allUnburned(4, 4);
			float[] values = new float[16];

			Path dp = writer.writeDamagePotential(values, grid, tempDir, "dp2.tif");
			Path ip = writer.writeIgnitionProbability(values, grid, tempDir, "ip2.tif");

			assertThat(ImageIO.read(dp.toFile())).isNotNull();
			assertThat(ImageIO.read(ip.toFile())).isNotNull();
		}
	}
}