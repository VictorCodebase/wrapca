package com.victorkithinji.wrap.wrapca.montecarlo;

import com.victorkithinji.wrap.wrapca.grid.CaGrid;
import com.victorkithinji.wrap.wrapca.grid.CellEnvironment;
import com.victorkithinji.wrap.wrapca.grid.CellStateEnum;
import com.victorkithinji.wrap.wrapca.grid.VegetationTypeEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class IgnitionSeedSamplerTest {

	private IgnitionSeedSampler sampler;
	private CaGrid grid3x3;

	@BeforeEach
	void setUp() {
		sampler = new IgnitionSeedSampler();
		int rows = 3;
		int cols = 3;

		CellEnvironment[][] envs = new CellEnvironment[3][3];
		int[][] stateOrdinals = new int[rows][cols];
		for (int r = 0; r < 3; r++)
			for (int c = 0; c < 3; c++) {
				envs[r][c] = new CellEnvironment(0.5f, 0.1f, 0f, 0f, 0f, VegetationTypeEnum.GRASSLAND);
				;
				stateOrdinals[r][c] = CellStateEnum.UNBURNED.ordinal();
			}
		grid3x3 = new CaGrid(stateOrdinals, envs, rows, cols, 100);
	}

	@Test
	void returnsExactlyNSeeds() {
		float[] weights = uniformWeights(9);
		List<Long> seeds = sampler.sample(grid3x3, weights, 50, 42L);
		assertThat(seeds).hasSize(50);
	}

	@Test
	void allReturnedIndicesAreInBounds() {
		float[] weights = uniformWeights(9);
		List<Long> seeds = sampler.sample(grid3x3, weights, 200, 7L);
		for (long idx : seeds) {
			assertThat(idx).isBetween(0L, 8L);
		}
	}

	@Test
	void singleHighWeightCellDominates() {
		// Cell index 4 (centre) has 99% of the weight
		float[] weights = new float[9];
		for (int i = 0; i < 9; i++) weights[i] = 0.01f;
		weights[4] = 100f;

		List<Long> seeds = sampler.sample(grid3x3, weights, 1000, 1L);
		long centre = seeds.stream().filter(s -> s == 4L).count();
		// Centre should be chosen overwhelmingly — at least 90% of draws
		assertThat(centre).isGreaterThan(900);
	}

	@Test
	void sameRandomSeedProducesIdenticalResults() {
		float[] weights = uniformWeights(9);
		List<Long> a = sampler.sample(grid3x3, weights, 20, 123L);
		List<Long> b = sampler.sample(grid3x3, weights, 20, 123L);
		assertThat(a).isEqualTo(b);
	}

	@Test
	void differentRandomSeedProducesDifferentResults() {
		float[] weights = uniformWeights(9);
		List<Long> a = sampler.sample(grid3x3, weights, 50, 1L);
		List<Long> b = sampler.sample(grid3x3, weights, 50, 9999L);
		// Extremely unlikely to be identical with 50 samples over 9 cells
		assertThat(a).isNotEqualTo(b);
	}

	@Test
	void uniformWeightsMeanAllCellsEventuallySelected() {
		float[] weights = uniformWeights(9);
		List<Long> seeds = sampler.sample(grid3x3, weights, 500, 42L);
		// With uniform weights and 500 draws over 9 cells, all must appear
		assertThat(new HashSet<>(seeds)).containsExactlyInAnyOrder(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
	}

	@Test
	void throwsWhenWeightsLengthMismatch() {
		float[] wrong = new float[5]; // grid is 3×3 = 9
		assertThatThrownBy(() -> sampler.sample(grid3x3, wrong, 10, 0L))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("weights length");
	}

	@Test
	void throwsWhenAllWeightsAreZero() {
		float[] zeros = new float[9];
		assertThatThrownBy(() -> sampler.sample(grid3x3, zeros, 10, 0L))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("zero");
	}

	// -----------------------------------------------------------------------

	private float[] uniformWeights(int size) {
		float[] w = new float[size];
		for (int i = 0; i < size; i++) w[i] = 1f;
		return w;
	}
}