package io.github.babygipsn.deathswap.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;

/** Picks dry, solid surface positions spread out across the round world. */
public final class SpawnFinder {
	private static final int ATTEMPTS = 60;

	private SpawnFinder() {
	}

	public static List<BlockPos> spread(ServerLevel level, int count, int radius, int minDistance) {
		RandomSource random = RandomSource.create();
		List<BlockPos> result = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			result.add(find(level, random, radius, result, minDistance));
		}
		return result;
	}

	public static BlockPos find(ServerLevel level, RandomSource random, int radius, List<BlockPos> others, int minDistance) {
		BlockPos fallback = null;
		for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
			// relax the distance requirement once it is clearly unsatisfiable
			int required = attempt < ATTEMPTS / 2 ? minDistance : minDistance / 2;
			int x = random.nextIntBetweenInclusive(-radius, radius);
			int z = random.nextIntBetweenInclusive(-radius, radius);
			if (!farEnough(x, z, others, required)) continue;
			if (attempt < ATTEMPTS - 10 && isWaterBiome(level, x, z)) continue; // cheap noise check before generating chunks

			BlockPos pos = surface(level, x, z);
			if (pos == null) continue;
			if (fallback == null) fallback = pos;
			if (isSafe(level, pos)) return pos;
		}
		if (fallback != null) return fallback;
		BlockPos origin = surface(level, 0, 0);
		return origin != null ? origin : new BlockPos(0, 100, 0);
	}

	private static boolean farEnough(int x, int z, List<BlockPos> others, int minDistance) {
		long min2 = (long) minDistance * minDistance;
		for (BlockPos p : others) {
			long dx = p.getX() - x;
			long dz = p.getZ() - z;
			if (dx * dx + dz * dz < min2) return false;
		}
		return true;
	}

	private static boolean isWaterBiome(ServerLevel level, int x, int z) {
		Holder<Biome> biome = level.getUncachedNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(level.getSeaLevel()), QuartPos.fromBlock(z));
		return biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER) || biome.is(BiomeTags.IS_DEEP_OCEAN);
	}

	private static BlockPos surface(ServerLevel level, int x, int z) {
		level.getChunk(x >> 4, z >> 4); // generates the chunk synchronously
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		if (y <= level.getMinY() + 1) return null;
		return new BlockPos(x, y, z);
	}

	private static boolean isSafe(ServerLevel level, BlockPos pos) {
		BlockState ground = level.getBlockState(pos.below());
		if (!ground.getFluidState().isEmpty()) return false;
		if (!ground.isSolidRender()) return false;
		return level.getBlockState(pos).getFluidState().isEmpty() && level.getBlockState(pos.above()).getFluidState().isEmpty();
	}
}
