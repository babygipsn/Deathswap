package io.github.babygipsn.deathswap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.babygipsn.deathswap.command.DeathswapCommand;
import io.github.babygipsn.deathswap.config.ConfigManager;
import io.github.babygipsn.deathswap.config.PlayerPrefs;
import io.github.babygipsn.deathswap.feature.StructurePlacers;
import io.github.babygipsn.deathswap.game.Fx;
import io.github.babygipsn.deathswap.game.GameManager;
import io.github.babygipsn.deathswap.hud.HudFont;
import io.github.babygipsn.deathswap.hud.HudManager;
import io.github.babygipsn.deathswap.lang.Lang;
import io.github.babygipsn.deathswap.net.DeathswapNetworking;
import io.github.babygipsn.deathswap.net.DeathswapNetworking.ConfigUpdatePayload;
import io.github.babygipsn.deathswap.net.DeathswapNetworking.HudPayload;
import io.github.babygipsn.deathswap.pack.ResourcePackManager;
import io.github.babygipsn.deathswap.util.Perms;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Deathswap implements ModInitializer {
	public static final String MOD_ID = "deathswap";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		ConfigManager.load();
		PlayerPrefs.load();
		Lang.load();
		HudFont.load();
		ResourcePackManager.load();
		DeathswapNetworking.registerPayloads();

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> DeathswapCommand.register(dispatcher));

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			GameManager.onServerStarted(server);
			ResourcePackManager.reconfigure();
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			GameManager.onServerStopping();
			ResourcePackManager.stopHost();
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			GameManager game = GameManager.getOrNull();
			if (game == null) return;
			game.tick();
			if (game.tickCount() % 10 == 0) HudManager.update(game);
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.player;
			if (!ServerPlayNetworking.canSend(player, HudPayload.TYPE)) {
				ResourcePackManager.send(player);
			}
			GameManager.get().onJoin(player);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			HudManager.onLeave(handler.player);
			ResourcePackManager.forget(handler.player);
			GameManager game = GameManager.getOrNull();
			if (game != null) game.onLeave(handler.player);
		});

		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			GameManager game = GameManager.getOrNull();
			if (game != null && entity instanceof ServerPlayer player) game.onDeath(player, source);
		});
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			GameManager game = GameManager.getOrNull();
			return game == null || !(entity instanceof ServerPlayer player) || game.allowDamage(player);
		});
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			GameManager game = GameManager.getOrNull();
			if (game != null && !alive) game.afterRespawn(newPlayer);
		});

		UseItemCallback.EVENT.register((player, level, hand) -> StructurePlacers.onUse(player, level, hand, null));
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> StructurePlacers.onUse(player, level, hand, hit));

		ServerPlayNetworking.registerGlobalReceiver(ConfigUpdatePayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (!Perms.isAdmin(player)) return;
			List<String> errors = new ArrayList<>();
			JsonObject changes = JsonParser.parseString(payload.json()).getAsJsonObject();
			for (Map.Entry<String, com.google.gson.JsonElement> change : changes.entrySet()) {
				ConfigManager.entry(change.getKey()).ifPresentOrElse(entry -> {
					String err = entry.set(change.getValue().getAsString());
					if (err != null) errors.add(entry.key + ": " + err);
				}, () -> errors.add(change.getKey() + ": unknown"));
			}
			onConfigChanged();
			if (errors.isEmpty()) Fx.msg(player, "deathswap.msg.config_saved", changes.size());
			else Fx.msg(player, "deathswap.err.bad_value", String.join("; ", errors));
		});

		LOGGER.info("Deathswap loaded");
	}

	/** Persists the config and applies settings that need more than a re-read. */
	public static void onConfigChanged() {
		ConfigManager.save();
		ResourcePackManager.reconfigure();
	}
}
