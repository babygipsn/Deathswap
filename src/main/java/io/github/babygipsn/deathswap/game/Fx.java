package io.github.babygipsn.deathswap.game;

import io.github.babygipsn.deathswap.lang.Lang;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/** Chat, title and sound helpers. All text is localized per receiving player. */
public final class Fx {
	private Fx() {
	}

	public static MutableComponent prefix() {
		return Component.literal("[").withStyle(ChatFormatting.DARK_GRAY)
				.append(Component.literal("Deathswap").withStyle(ChatFormatting.RED))
				.append(Component.literal("] ").withStyle(ChatFormatting.DARK_GRAY));
	}

	public static void msg(ServerPlayer player, String key, Object... args) {
		player.sendSystemMessage(prefix().append(Lang.tr(player, key, args).withStyle(ChatFormatting.GRAY)));
	}

	public static void broadcast(MinecraftServer server, String key, Object... args) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			msg(p, key, args);
		}
	}

	public static void title(ServerPlayer player, Component title, Component subtitle, int fadeIn, int stay, int fadeOut) {
		player.connection.send(new ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut));
		player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
		player.connection.send(new ClientboundSetTitleTextPacket(title));
	}

	public static void sound(ServerPlayer player, SoundEvent sound, float volume, float pitch) {
		sound(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), volume, pitch);
	}

	public static void sound(ServerPlayer player, Holder<SoundEvent> sound, float volume, float pitch) {
		player.connection.send(new ClientboundSoundPacket(sound, SoundSource.MASTER,
				player.getX(), player.getY(), player.getZ(), volume, pitch, player.getRandom().nextLong()));
	}

	public static String time(int ticks) {
		int seconds = Math.max(0, (ticks + 19) / 20);
		int h = seconds / 3600;
		int m = (seconds / 60) % 60;
		int s = seconds % 60;
		return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%02d:%02d", m, s);
	}
}
