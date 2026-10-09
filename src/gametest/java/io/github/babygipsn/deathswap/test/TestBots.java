package io.github.babygipsn.deathswap.test;

import com.mojang.authlib.GameProfile;
import io.github.babygipsn.deathswap.mixin.ServerGamePacketListenerImplAccessor;
import io.github.babygipsn.deathswap.test.mixin.ConnectionAccessor;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Server-side fake players that count as real online players (in the player list), for multi-player tests. */
public final class TestBots {
	private static final Set<UUID> BOTS = ConcurrentHashMap.newKeySet();
	private static boolean ticking;

	private TestBots() {
	}

	public static ServerPlayer spawn(MinecraftServer server, String name) {
		if (!ticking) {
			ticking = true;
			// acknowledge teleports / level loads every tick, like a real client would
			ServerTickEvents.END_SERVER_TICK.register(s -> {
				for (ServerPlayer p : s.getPlayerList().getPlayers()) {
					if (!BOTS.contains(p.getUUID()) || !p.isAlive()) continue;
					p.hasChangedDimension();
					if (!p.connection.hasClientLoaded()) p.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
				}
			});
		}
		GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)), name);
		BOTS.add(profile.id());
		ServerPlayer player = new ServerPlayer(server, server.overworld(), profile, ClientInformation.createDefault());
		server.getPlayerList().placeNewPlayer(new FakeConnection(), player, CommonListenerCookie.createInitial(profile, false));
		ServerPlayer placed = server.getPlayerList().getPlayerByName(name);
		placed.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket()); // a real client sends this once loaded
		return placed;
	}

	/** What the respawn button does, for a bot. */
	public static ServerPlayer respawn(MinecraftServer server, ServerPlayer bot) {
		ServerPlayer respawned = server.getPlayerList().respawn(bot, false, Entity.RemovalReason.KILLED);
		respawned.connection.player = respawned;
		((ServerGamePacketListenerImplAccessor) respawned.connection).deathswap$restartClientLoadTimerAfterRespawn();
		respawned.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
		return respawned;
	}

	private static final class FakeConnection extends Connection {
		FakeConnection() {
			super(PacketFlow.SERVERBOUND);
			((ConnectionAccessor) (Object) this).deathswap$setChannel(new EmbeddedChannel());
		}

		@Override
		public void setReadOnly() {
		}

		@Override
		public void handleDisconnection() {
		}

		@Override
		public void setListenerForServerboundHandshake(PacketListener listener) {
		}

		@Override
		public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocol, T listener) {
		}
	}
}
