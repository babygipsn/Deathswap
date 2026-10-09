package io.github.babygipsn.deathswap.feature;

import io.github.babygipsn.deathswap.Deathswap;
import io.github.babygipsn.deathswap.config.ConfigManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Gives the configured starting items, e.g. "minecraft:bread 8". */
public final class StartKit {
	private StartKit() {
	}

	public static void give(ServerPlayer player) {
		if (!ConfigManager.get().startKit.enabled) return;
		for (String entry : ConfigManager.get().startKit.items) {
			ItemStack stack = parse(entry);
			if (stack != null) giveOrDrop(player, stack);
		}
	}

	public static ItemStack parse(String entry) {
		String[] parts = entry.trim().split("\\s+");
		if (parts.length == 0 || parts[0].isEmpty()) return null;
		Identifier id = Identifier.tryParse(parts[0]);
		Item item = id == null ? null : BuiltInRegistries.ITEM.getValue(id);
		if (item == null || item == Items.AIR) {
			Deathswap.LOGGER.warn("Start kit: unknown item '{}'", parts[0]);
			return null;
		}
		int count = 1;
		if (parts.length > 1) {
			try {
				count = Math.max(1, Integer.parseInt(parts[1]));
			} catch (NumberFormatException ignored) {
			}
		}
		return new ItemStack(item, count);
	}

	public static void giveOrDrop(ServerPlayer player, ItemStack stack) {
		if (!player.getInventory().add(stack) && !stack.isEmpty()) {
			player.drop(stack, false, Prediction.SERVER_ONLY);
		}
	}
}
