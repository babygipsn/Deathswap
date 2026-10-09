package io.github.babygipsn.deathswap.net;

import io.github.babygipsn.deathswap.Deathswap;
import io.github.babygipsn.deathswap.hud.HudLine;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/** Payloads exchanged with clients that have the mod installed. Vanilla clients never see these. */
public final class DeathswapNetworking {
	private DeathswapNetworking() {
	}

	/** Server -> client: the HUD lines to draw natively at the left edge. Empty list hides the HUD. */
	public record HudPayload(List<HudLine> lines) implements CustomPacketPayload {
		public static final Type<HudPayload> TYPE = new Type<>(Deathswap.id("hud"));
		public static final StreamCodec<RegistryFriendlyByteBuf, HudPayload> CODEC = StreamCodec.composite(
				HudLine.CODEC.apply(ByteBufCodecs.list(64)), HudPayload::lines,
				HudPayload::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Server -> client: open the config screen. {@code json} is {@link io.github.babygipsn.deathswap.config.ConfigManager#toSchemaJson()}. */
	public record OpenConfigPayload(String json) implements CustomPacketPayload {
		public static final Type<OpenConfigPayload> TYPE = new Type<>(Deathswap.id("open_config"));
		public static final StreamCodec<FriendlyByteBuf, OpenConfigPayload> CODEC = StreamCodec.composite(
				ByteBufCodecs.stringUtf8(1 << 20), OpenConfigPayload::json, OpenConfigPayload::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client -> server: changed config values as a JSON object of key -> string value. */
	public record ConfigUpdatePayload(String json) implements CustomPacketPayload {
		public static final Type<ConfigUpdatePayload> TYPE = new Type<>(Deathswap.id("config_update"));
		public static final StreamCodec<FriendlyByteBuf, ConfigUpdatePayload> CODEC = StreamCodec.composite(
				ByteBufCodecs.stringUtf8(1 << 20), ConfigUpdatePayload::json, ConfigUpdatePayload::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void registerPayloads() {
		PayloadTypeRegistry.clientboundPlay().register(HudPayload.TYPE, HudPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(OpenConfigPayload.TYPE, OpenConfigPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ConfigUpdatePayload.TYPE, ConfigUpdatePayload.CODEC);
	}
}
