package io.github.babygipsn.deathswap.feature;

import io.github.babygipsn.deathswap.config.ConfigManager;
import io.github.babygipsn.deathswap.config.DeathswapConfig;
import io.github.babygipsn.deathswap.game.Fx;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;

import java.util.ArrayList;
import java.util.List;

/** Hands out a random item (or, rarely, a structure placer) every few seconds. */
public final class RandomItems {
	private static final RandomSource RANDOM = RandomSource.create();

	private RandomItems() {
	}

	public static void give(ServerPlayer player) {
		DeathswapConfig cfg = ConfigManager.get();
		if (cfg.structurePlacers.enabled && RANDOM.nextDouble() < cfg.randomItems.placerChance) {
			ItemStack placer = StructurePlacers.createRandom(player);
			if (placer != null) {
				StartKit.giveOrDrop(player, placer);
				Fx.sound(player, SoundEvents.AMETHYST_BLOCK_CHIME, 1f, 1.2f);
				Fx.msg(player, "deathswap.msg.got_placer", placer.getHoverName());
				return;
			}
		}
		List<Item> pool = eligible(player.level().enabledFeatures(), cfg.randomItems);
		if (pool.isEmpty()) return;
		Item item = pool.get(RANDOM.nextInt(pool.size()));
		ItemStack stack = new ItemStack(item);
		int max = stack.getMaxStackSize();
		int count = cfg.randomItems.randomStackSize ? 1 + RANDOM.nextInt(max) : Math.min(cfg.randomItems.amount, max);
		stack.setCount(Math.max(1, count));
		StartKit.giveOrDrop(player, stack);
		Fx.sound(player, SoundEvents.ITEM_PICKUP, 0.6f, 1.4f);
	}

	private static List<Item> eligible(FeatureFlagSet features, DeathswapConfig.RandomItems cfg) {
		List<Identifier> blockedIds = new ArrayList<>();
		List<TagKey<Item>> blockedTags = new ArrayList<>();
		for (String entry : cfg.blacklist) {
			String s = entry.trim();
			if (s.startsWith("#")) {
				Identifier tag = Identifier.tryParse(s.substring(1));
				if (tag != null) blockedTags.add(TagKey.create(Registries.ITEM, tag));
			} else {
				Identifier id = Identifier.tryParse(s);
				if (id != null) blockedIds.add(id);
			}
		}
		List<Item> list = new ArrayList<>();
		for (Item item : BuiltInRegistries.ITEM) {
			if (!item.isEnabled(features)) continue;
			if (!cfg.allowSpawnEggs && item instanceof SpawnEggItem) continue;
			Holder<Item> holder = BuiltInRegistries.ITEM.wrapAsHolder(item);
			if (blockedIds.contains(BuiltInRegistries.ITEM.getKey(item))) continue;
			if (blockedTags.stream().anyMatch(holder::is)) continue;
			list.add(item);
		}
		return list;
	}
}
