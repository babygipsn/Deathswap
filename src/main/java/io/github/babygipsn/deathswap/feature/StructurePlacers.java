package io.github.babygipsn.deathswap.feature;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.babygipsn.deathswap.Deathswap;
import io.github.babygipsn.deathswap.config.ConfigManager;
import io.github.babygipsn.deathswap.game.Fx;
import io.github.babygipsn.deathswap.lang.Lang;
import io.github.babygipsn.deathswap.world.RoundWorld;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.commands.PlaceCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * "Structure placers": an item named after a random structure (e.g. "Bastion Placer") that builds that
 * structure where it is used. Implemented with a vanilla knowledge book + custom data, so vanilla
 * clients see it too.
 */
public final class StructurePlacers {
	private static final String TAG = "deathswap_placer";
	private static final RandomSource RANDOM = RandomSource.create();

	private StructurePlacers() {
	}

	public static List<Holder.Reference<Structure>> available(Level level) {
		List<String> blacklist = ConfigManager.get().structurePlacers.blacklist;
		return level.registryAccess().lookupOrThrow(Registries.STRUCTURE).listElements()
				.filter(h -> !blacklist.contains(h.key().identifier().toString()))
				.toList();
	}

	public static @Nullable ItemStack createRandom(ServerPlayer player) {
		List<Holder.Reference<Structure>> all = available(player.level());
		if (all.isEmpty()) return null;
		return create(player, all.get(RANDOM.nextInt(all.size())));
	}

	public static ItemStack create(@Nullable ServerPlayer forPlayer, Holder.Reference<Structure> structure) {
		Identifier id = structure.key().identifier();
		String name = displayName(forPlayer, id);
		ItemStack stack = new ItemStack(Items.KNOWLEDGE_BOOK);
		stack.set(DataComponents.ITEM_NAME, Component.literal(Lang.str(forPlayer, "deathswap.item.placer", name)).withStyle(ChatFormatting.LIGHT_PURPLE));
		stack.set(DataComponents.LORE, new ItemLore(List.of(
				Component.literal(Lang.str(forPlayer, "deathswap.item.placer_lore", name)).withStyle(ChatFormatting.GRAY),
				Component.literal(id.toString()).withStyle(ChatFormatting.DARK_GRAY))));
		stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		CompoundTag tag = new CompoundTag();
		tag.putString(TAG, id.toString());
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return stack;
	}

	/** "minecraft:bastion_remnant" -> "Bastion" (from the lang file) or "Bastion Remnant" as fallback. */
	public static String displayName(@Nullable ServerPlayer player, Identifier id) {
		String key = "deathswap.structure." + id.getPath();
		String translated = Lang.raw(player, key);
		if (!translated.equals(key)) return translated;
		return Arrays.stream(id.getPath().split("[_/]"))
				.filter(s -> !s.isEmpty())
				.map(s -> s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1))
				.collect(Collectors.joining(" "));
	}

	public static @Nullable Identifier placerStructure(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) return null;
		return data.copyTag().getString(TAG).map(Identifier::tryParse).orElse(null);
	}

	/** Shared handler for right-click in the air and on a block. */
	public static InteractionResult onUse(Player player, Level level, InteractionHand hand, @Nullable BlockHitResult hit) {
		ItemStack stack = player.getItemInHand(hand);
		Identifier structureId = placerStructure(stack);
		if (structureId == null) return InteractionResult.PASS;
		if (level.isClientSide() || !(player instanceof ServerPlayer sp) || !(level instanceof ServerLevel serverLevel)) {
			return InteractionResult.SUCCESS;
		}
		if (RoundWorld.of(level) == null && !sp.isCreative()) {
			Fx.msg(sp, "deathswap.msg.placer_lobby");
			return InteractionResult.FAIL;
		}
		var structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).get(ResourceKey.create(Registries.STRUCTURE, structureId));
		if (structure.isEmpty()) {
			Fx.msg(sp, "deathswap.msg.placer_unknown", structureId.toString());
			return InteractionResult.FAIL;
		}
		BlockPos target = hit != null
				? hit.getBlockPos().relative(hit.getDirection())
				: BlockPos.containing(sp.position().add(Vec3.directionFromRotation(0, sp.getYRot()).scale(16)));
		try {
			PlaceCommand.placeStructure(sp.createCommandSourceStack().withLevel(serverLevel).withSuppressedOutput(), structure.get(), target);
		} catch (CommandSyntaxException e) {
			Fx.msg(sp, "deathswap.msg.placer_failed", displayName(sp, structureId));
			return InteractionResult.FAIL;
		} catch (RuntimeException e) {
			Deathswap.LOGGER.error("Placing {} failed", structureId, e);
			Fx.msg(sp, "deathswap.msg.placer_failed", displayName(sp, structureId));
			return InteractionResult.FAIL;
		}
		if (!sp.isCreative()) stack.shrink(1);
		serverLevel.playSound(null, target, SoundEvents.EVOKER_CAST_SPELL, net.minecraft.sounds.SoundSource.PLAYERS, 1f, 0.8f);
		Fx.msg(sp, "deathswap.msg.placer_done", displayName(sp, structureId));
		return InteractionResult.SUCCESS;
	}
}
