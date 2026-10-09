package io.github.babygipsn.deathswap.client;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import io.github.babygipsn.deathswap.Deathswap;
import io.github.babygipsn.deathswap.net.DeathswapNetworking.HudPayload;
import io.github.babygipsn.deathswap.net.DeathswapNetworking.OpenConfigPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.network.chat.Component;

public class DeathswapClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientConfig.load();

		// -Ddeathswap.vanillaHud=true makes this client look like a vanilla one (no payload channels),
		// which is handy for testing the resource-pack HUD.
		if (Boolean.getBoolean("deathswap.vanillaHud")) {
			Deathswap.LOGGER.info("deathswap.vanillaHud set: native HUD disabled");
			return;
		}

		ClientPlayNetworking.registerGlobalReceiver(HudPayload.TYPE, (payload, context) -> HudRenderer.setLines(payload.lines()));
		ClientPlayNetworking.registerGlobalReceiver(OpenConfigPayload.TYPE,
				(payload, context) -> context.client().setScreenAndShow(new ConfigScreen(payload.json())));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> HudRenderer.clear());

		HudElementRegistry.attachElementAfter(VanillaHudElements.BOSS_BAR, Deathswap.id("hud"), HudRenderer::render);

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
				ClientCommands.literal("dshud")
						.then(ClientCommands.literal("x").then(ClientCommands.argument("px", IntegerArgumentType.integer(0, 4000)).executes(ctx -> {
							ClientConfig.get().hudX = IntegerArgumentType.getInteger(ctx, "px");
							return saved(ctx.getSource());
						})))
						.then(ClientCommands.literal("y").then(ClientCommands.argument("px", IntegerArgumentType.integer(-1, 4000)).executes(ctx -> {
							ClientConfig.get().hudY = IntegerArgumentType.getInteger(ctx, "px");
							return saved(ctx.getSource());
						})))
						.then(ClientCommands.literal("scale").then(ClientCommands.argument("factor", FloatArgumentType.floatArg(0.25f, 4f)).executes(ctx -> {
							ClientConfig.get().scale = FloatArgumentType.getFloat(ctx, "factor");
							return saved(ctx.getSource());
						})))
						.then(ClientCommands.literal("background").executes(ctx -> {
							ClientConfig.get().background = !ClientConfig.get().background;
							return saved(ctx.getSource());
						}))
						.then(ClientCommands.literal("reset").executes(ctx -> {
							ClientConfig defaults = new ClientConfig();
							ClientConfig cfg = ClientConfig.get();
							cfg.hudX = defaults.hudX;
							cfg.hudY = defaults.hudY;
							cfg.scale = defaults.scale;
							cfg.background = defaults.background;
							return saved(ctx.getSource());
						}))));
	}

	private static int saved(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source) {
		ClientConfig.save();
		ClientConfig c = ClientConfig.get();
		source.sendFeedback(Component.literal("Deathswap HUD: x=" + c.hudX + " y=" + c.hudY + " scale=" + c.scale + " background=" + c.background));
		return 1;
	}
}
