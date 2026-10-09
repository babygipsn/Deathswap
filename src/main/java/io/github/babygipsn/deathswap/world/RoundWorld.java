package io.github.babygipsn.deathswap.world;

import io.github.babygipsn.deathswap.Deathswap;
import io.github.babygipsn.deathswap.config.DeathswapConfig;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.jspecify.annotations.Nullable;
import xyz.nucleoid.fantasy.Fantasy;
import xyz.nucleoid.fantasy.RuntimeLevelConfig;
import xyz.nucleoid.fantasy.RuntimeLevelHandle;

import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The temporary overworld / nether / end of one round. Created fresh each round (new terrain, no
 * traps from the previous round) and deleted when the round ends.
 */
public final class RoundWorld {
	/** The world of the round in progress; read by the portal and respawn mixins. */
	private static @Nullable RoundWorld active;

	public final long seed;
	private final RuntimeLevelHandle overworld;
	private final @Nullable RuntimeLevelHandle nether;
	private final @Nullable RuntimeLevelHandle end;

	private RoundWorld(long seed, RuntimeLevelHandle overworld, @Nullable RuntimeLevelHandle nether, @Nullable RuntimeLevelHandle end) {
		this.seed = seed;
		this.overworld = overworld;
		this.nether = nether;
		this.end = end;
	}

	public static @Nullable RoundWorld active() {
		return active;
	}

	public static void setActive(@Nullable RoundWorld world) {
		active = world;
	}

	/** The active round world the level belongs to, or null if the level is not part of it. */
	public static @Nullable RoundWorld of(Level level) {
		RoundWorld w = active;
		return w != null && w.contains(level) ? w : null;
	}

	/** Portal code compares against vanilla keys; a round level answers with the key it stands in for. */
	public static ResourceKey<Level> aliasKey(ServerLevel level, ResourceKey<Level> actual) {
		RoundWorld w = of(level);
		ResourceKey<Level> alias = w != null ? w.vanillaKeyOf(level) : null;
		return alias != null ? alias : actual;
	}

	/**
	 * Portal target lookup: from inside a round world, vanilla keys resolve to the round's own levels.
	 * A disabled nether/end resolves to null, which makes the portal do nothing.
	 */
	public static @Nullable ServerLevel resolveLinked(ServerLevel from, ResourceKey<Level> key, java.util.function.Supplier<ServerLevel> vanilla) {
		RoundWorld w = of(from);
		if (w == null) return vanilla.get();
		if (key == Level.OVERWORLD || key == Level.NETHER || key == Level.END) return w.byVanillaKey(key);
		return vanilla.get();
	}

	public static RoundWorld create(MinecraftServer server, DeathswapConfig cfg, int round) {
		long seed = pickSeed(server, cfg.world);
		Map<ResourceKey<LevelStem>, LevelStem> stems = presetDimensions(server, cfg.world.worldPreset);
		String tag = "r" + round + "_" + Long.toString(ThreadLocalRandom.current().nextLong() & 0xFFFFFFL, 36);
		Fantasy fantasy = Fantasy.get(server);

		RuntimeLevelHandle overworld = fantasy.openTemporaryLevel(Deathswap.id(tag + "_overworld"),
				levelConfig(stems.get(LevelStem.OVERWORLD), BuiltinDimensionTypes.OVERWORLD, seed, cfg, true));
		RuntimeLevelHandle nether = null;
		if (cfg.world.netherEnabled && stems.containsKey(LevelStem.NETHER)) {
			nether = fantasy.openTemporaryLevel(Deathswap.id(tag + "_nether"),
					levelConfig(stems.get(LevelStem.NETHER), BuiltinDimensionTypes.NETHER, seed, cfg, false));
		}
		RuntimeLevelHandle end = null;
		if (cfg.world.endEnabled && stems.containsKey(LevelStem.END)) {
			end = fantasy.openTemporaryLevel(Deathswap.id(tag + "_end"),
					levelConfig(stems.get(LevelStem.END), BuiltinDimensionTypes.END, seed, cfg, false));
		}

		RoundWorld world = new RoundWorld(seed, overworld, nether, end);
		if (cfg.world.worldBorderSize > 0) {
			ServerLevel ow = world.overworld();
			ow.getWorldBorder().setCenter(0, 0);
			ow.getWorldBorder().setSize(cfg.world.worldBorderSize);
		}
		Deathswap.LOGGER.info("Created round {} worlds ({}), seed {}", round, tag, seed);
		return world;
	}

	private static RuntimeLevelConfig levelConfig(LevelStem stem, ResourceKey<DimensionType> type, long seed, DeathswapConfig cfg, boolean tickTime) {
		return new RuntimeLevelConfig()
				.setDimensionType(type)
				.setGenerator(stem.generator())
				.setSeed(seed)
				.setShouldTickTime(tickTime)
				.setMirrorOverworldGameRules(true)
				.setMirrorOverworldDifficulty(true)
				.setGameRule(GameRules.KEEP_INVENTORY, cfg.game.keepInventory);
	}

	private static long pickSeed(MinecraftServer server, DeathswapConfig.World cfg) {
		return switch (cfg.seedMode) {
			case RANDOM -> ThreadLocalRandom.current().nextLong();
			case MAIN_WORLD -> server.getWorldGenSettings().options().seed();
			case FIXED -> {
				OptionalLong parsed = WorldOptions.parseSeed(cfg.fixedSeed);
				yield parsed.isPresent() ? parsed.getAsLong() : 0L;
			}
		};
	}

	/** Dimension generators from a world preset (minecraft:normal by default, datapack presets work too). */
	private static Map<ResourceKey<LevelStem>, LevelStem> presetDimensions(MinecraftServer server, String presetId) {
		var registry = server.registryAccess().lookupOrThrow(Registries.WORLD_PRESET);
		Identifier id = Identifier.tryParse(presetId);
		WorldPreset preset = id == null ? null : registry.getValue(id);
		if (preset == null) {
			Deathswap.LOGGER.warn("Unknown world preset '{}', using minecraft:normal", presetId);
			preset = registry.getValueOrThrow(WorldPresets.NORMAL);
		}
		return preset.createWorldDimensions().dimensions();
	}

	public ServerLevel overworld() {
		return this.overworld.asLevel();
	}

	public @Nullable ServerLevel nether() {
		return this.nether != null ? this.nether.asLevel() : null;
	}

	public @Nullable ServerLevel end() {
		return this.end != null ? this.end.asLevel() : null;
	}

	public boolean contains(Level level) {
		return level == this.overworld() || level == this.nether() || level == this.end();
	}

	/** Maps a vanilla dimension key to this round's level (used to link portals). */
	public @Nullable ServerLevel byVanillaKey(ResourceKey<Level> key) {
		if (key == Level.OVERWORLD) return this.overworld();
		if (key == Level.NETHER) return this.nether();
		if (key == Level.END) return this.end();
		return null;
	}

	/** The vanilla key this round level stands in for (so vanilla portal logic treats it like the real one). */
	public @Nullable ResourceKey<Level> vanillaKeyOf(Level level) {
		if (level == this.overworld()) return Level.OVERWORLD;
		if (level == this.nether()) return Level.NETHER;
		if (level == this.end()) return Level.END;
		return null;
	}

	/** Deletes all three levels (players still inside are moved to the main world by Fantasy). */
	public void delete() {
		if (active == this) active = null;
		this.overworld.delete();
		if (this.nether != null) this.nether.delete();
		if (this.end != null) this.end.delete();
	}
}
