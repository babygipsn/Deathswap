package io.github.babygipsn.deathswap.feature;

import io.github.babygipsn.deathswap.config.ConfigManager;
import io.github.babygipsn.deathswap.config.DeathswapConfig;
import io.github.babygipsn.deathswap.game.Fx;
import io.github.babygipsn.deathswap.game.GameManager;
import io.github.babygipsn.deathswap.lang.Lang;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/** Optional chaos events that hit every participant at once. */
public final class RandomEvents {
	private static final RandomSource RANDOM = RandomSource.create();

	private static final List<Holder<MobEffect>> RANDOM_EFFECTS = List.of(
			MobEffects.SPEED, MobEffects.SLOWNESS, MobEffects.HASTE, MobEffects.MINING_FATIGUE, MobEffects.STRENGTH,
			MobEffects.JUMP_BOOST, MobEffects.NAUSEA, MobEffects.REGENERATION, MobEffects.FIRE_RESISTANCE,
			MobEffects.INVISIBILITY, MobEffects.BLINDNESS, MobEffects.NIGHT_VISION, MobEffects.HUNGER, MobEffects.WEAKNESS,
			MobEffects.POISON, MobEffects.GLOWING, MobEffects.SLOW_FALLING, MobEffects.DARKNESS, MobEffects.RESISTANCE);

	private static final List<EntityType<?>> SWARM_MOBS = List.of(
			EntityTypes.ZOMBIE, EntityTypes.SKELETON, EntityTypes.SPIDER, EntityTypes.CREEPER, EntityTypes.WITCH, EntityTypes.SILVERFISH);

	public enum Event {
		TNT_RAIN(c -> c.tntRain, (p, l) -> {
			for (int i = 0; i < 4; i++) {
				PrimedTnt tnt = new PrimedTnt(l, p.getX() + RANDOM.nextInt(7) - 3, p.getY() + 12 + i * 2, p.getZ() + RANDOM.nextInt(7) - 3, null);
				tnt.setFuse(50 + RANDOM.nextInt(40));
				l.addFreshEntity(tnt);
			}
		}),
		ANVIL_RAIN(c -> c.anvilRain, (p, l) -> {
			for (int i = 0; i < 5; i++) {
				BlockPos pos = BlockPos.containing(p.getX() + RANDOM.nextInt(5) - 2, p.getY() + 14 + i, p.getZ() + RANDOM.nextInt(5) - 2);
				if (l.getBlockState(pos).isAir()) FallingBlockEntity.fall(l, pos, Blocks.ANVIL.defaultBlockState());
			}
		}),
		LIGHTNING(c -> c.lightning, (p, l) -> {
			for (int i = 0; i < 3; i++) {
				LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.create(l, EntitySpawnReason.EVENT);
				if (bolt == null) continue;
				BlockPos pos = l.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING,
						BlockPos.containing(p.getX() + RANDOM.nextInt(9) - 4, p.getY(), p.getZ() + RANDOM.nextInt(9) - 4));
				bolt.snapTo(Vec3.atBottomCenterOf(pos));
				l.addFreshEntity(bolt);
			}
		}),
		RANDOM_EFFECT(c -> c.randomEffect, (p, l) -> {
			Holder<MobEffect> effect = RANDOM_EFFECTS.get(RANDOM.nextInt(RANDOM_EFFECTS.size()));
			p.addEffect(new MobEffectInstance(effect, 20 * 25, RANDOM.nextInt(2)));
		}),
		LEVITATION(c -> c.levitation, (p, l) -> p.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 20 * 6, 1))),
		MOB_SWARM(c -> c.mobSwarm, (p, l) -> {
			for (int i = 0; i < 3; i++) {
				EntityType<?> type = SWARM_MOBS.get(RANDOM.nextInt(SWARM_MOBS.size()));
				double angle = RANDOM.nextDouble() * Math.PI * 2;
				int dist = 4 + RANDOM.nextInt(5);
				BlockPos pos = l.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
						BlockPos.containing(p.getX() + Math.cos(angle) * dist, p.getY(), p.getZ() + Math.sin(angle) * dist));
				if (Math.abs(pos.getY() - p.getY()) > 12) pos = p.blockPosition(); // underground / up a tower
				type.spawn(l, pos, EntitySpawnReason.EVENT);
			}
		}),
		INVENTORY_SHUFFLE(c -> c.inventoryShuffle, (p, l) -> {
			var inv = p.getInventory();
			List<ItemStack> items = new ArrayList<>();
			for (int i = 0; i < 36; i++) items.add(inv.getItem(i));
			Collections.shuffle(items);
			for (int i = 0; i < 36; i++) inv.setItem(i, items.get(i));
		}),
		SPEED_BOOST(c -> c.speedBoost, (p, l) -> {
			p.addEffect(new MobEffectInstance(MobEffects.SPEED, 20 * 30, 2));
			p.addEffect(new MobEffectInstance(MobEffects.HASTE, 20 * 30, 1));
		});

		private final Predicate<DeathswapConfig.Events> enabled;
		private final BiConsumer<ServerPlayer, ServerLevel> action;

		Event(Predicate<DeathswapConfig.Events> enabled, BiConsumer<ServerPlayer, ServerLevel> action) {
			this.enabled = enabled;
			this.action = action;
		}

		public String langKey() {
			return "deathswap.event." + this.name().toLowerCase(java.util.Locale.ROOT);
		}
	}

	private RandomEvents() {
	}

	/** Runs {@code forced}, or a random enabled event when null. */
	public static void trigger(GameManager game, @Nullable Event forced) {
		Event event = forced;
		if (event == null) {
			List<Event> enabled = Arrays.stream(Event.values()).filter(e -> e.enabled.test(ConfigManager.get().events)).toList();
			if (enabled.isEmpty()) return;
			event = enabled.get(RANDOM.nextInt(enabled.size()));
		}
		for (ServerPlayer p : game.roundPlayers()) {
			Fx.title(p, Lang.tr(p, "deathswap.title.event").withStyle(ChatFormatting.RED),
					Lang.tr(p, event.langKey()).withStyle(ChatFormatting.YELLOW), 5, 40, 10);
			Fx.sound(p, SoundEvents.EVOKER_PREPARE_WOLOLO, 1f, 1f);
		}
		for (ServerPlayer p : game.activeParticipants()) {
			if (p.isAlive() && !p.isSpectator()) event.action.accept(p, p.level());
		}
	}
}
