package io.github.babygipsn.deathswap.test;

import io.github.babygipsn.deathswap.client.ConfigScreen;
import io.github.babygipsn.deathswap.feature.StructurePlacers;
import io.github.babygipsn.deathswap.game.GameManager;
import io.github.babygipsn.deathswap.game.PlayerState;
import io.github.babygipsn.deathswap.pack.ResourcePackManager;
import io.github.babygipsn.deathswap.world.RoundWorld;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Properties;

/**
 * End-to-end test: a real client (Alice) plus a bot (Bob) on a dedicated server play a short game.
 * Runs headless under Xvfb; screenshots land in build/run/clientGameTest/screenshots.
 * With -Ddeathswap.vanillaHud=true the client hides the mod's channels and uses the resource-pack HUD.
 */
public class DeathswapClientGameTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("deathswap-test");
	private static final int MINUTE = 20 * 60;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		boolean vanillaHud = Boolean.getBoolean("deathswap.vanillaHud");
		String tag = vanillaHud ? "pack_" : "mod_";
		Properties props = new Properties();
		props.setProperty("level-type", "minecraft:flat");
		props.setProperty("spawn-protection", "0");

		try (TestDedicatedServerContext server = ctx.worldBuilder().createServer(props)) {
			for (String cmd : List.of(
					"ds config set game.rounds 2",
					"ds config set game.roundSeconds 600",
					"ds config set game.swapIntervalSeconds 300",
					"ds config set game.intermissionSeconds 3",
					"ds config set lobby.startCountdownSeconds 1",
					"ds config set lobby.autoStart false",
					"ds config set events.enabled false",
					"ds config set world.spreadRadius 300",
					"ds config set world.minPlayerDistance 100",
					"ds config set resourcePack.hostAddress 127.0.0.1",
					"ds config set resourcePack.mode BUILTIN_HOST")) {
				server.runCommand(cmd);
			}

			try (TestDedicatedServerConnection conn = server.connect()) {
				conn.waitForChunksRender();
				if (vanillaHud) {
					// the pack prompt ("Yes"/"No") appears for servers with a non-required pack
					ctx.waitFor(mc -> mc.gui.screen() != null, 20 * 20);
					ctx.takeScreenshot(tag + "00_pack_prompt");
					ctx.clickScreenButton("gui.yes");
					server.waitFor(s -> s.getPlayerList().getPlayers().stream()
							.anyMatch(p -> ResourcePackManager.status(p) == ResourcePackManager.Status.LOADED), MINUTE);
					LOG.info("resource pack loaded by client");
				}

				server.runOnServer(s -> TestBots.spawn(s, "Bob"));
				ctx.waitTicks(40);
				ctx.takeScreenshot(tag + "01_lobby");

				// ---- start
				server.runCommand("ds start");
				server.waitFor(s -> game().state() == GameManager.State.RUNNING, MINUTE);
				conn.waitForChunksRender();
				ctx.waitTicks(60);
				check(server, s -> {
					RoundWorld w = RoundWorld.active();
					return w != null && w.contains(alice(s).level()) && w.contains(bob(s).level());
				}, "both players are in the round world");
				check(server, s -> alice(s).getInventory().countItem(net.minecraft.world.item.Items.BREAD) == 8, "start kit given");
				ctx.takeScreenshot(tag + "02_round1");

				// ---- swap
				Vec3[] before = server.computeOnServer(s -> new Vec3[]{alice(s).position(), bob(s).position()});
				server.runCommand("ds swap");
				ctx.waitTicks(20);
				Vec3[] after = server.computeOnServer(s -> new Vec3[]{alice(s).position(), bob(s).position()});
				LOG.info("before {} / after {}", List.of(before), List.of(after));
				assertTrue(after[0].distanceTo(before[1]) < 1.5 && after[1].distanceTo(before[0]) < 1.5, "players swapped positions");
				conn.waitForChunksRender();
				ctx.waitTicks(20);
				ctx.takeScreenshot(tag + "03_after_swap");

				// ---- death right after a swap = trap kill for the other player
				server.runCommand("kill Bob");
				ctx.waitTicks(5);
				check(server, s -> stats(s, "Bob").deaths == 1, "Bob has 1 death");
				check(server, s -> stats(s, "Alice").trapKills == 1, "Alice got the trap kill");
				server.runOnServer(s -> TestBots.respawn(s, bob(s)));
				ctx.waitTicks(5);
				check(server, s -> RoundWorld.active().contains(bob(s).level()) && bob(s).isAlive(), "Bob respawned inside the round world");

				// ---- random event
				server.runCommand("ds event levitation");
				ctx.waitTicks(5);
				check(server, s -> alice(s).hasEffect(MobEffects.LEVITATION), "levitation event applied");
				server.runCommand("effect clear Alice");

				// ---- structure placer
				InteractionResult placed = server.computeOnServer(s -> {
					ServerPlayer a = alice(s);
					var igloo = s.registryAccess().lookupOrThrow(Registries.STRUCTURE)
							.get(ResourceKey.create(Registries.STRUCTURE, Identifier.withDefaultNamespace("igloo"))).orElseThrow();
					a.setItemInHand(InteractionHand.MAIN_HAND, StructurePlacers.create(a, igloo));
					return StructurePlacers.onUse(a, a.level(), InteractionHand.MAIN_HAND, null);
				});
				LOG.info("placer result: {}", placed);
				assertTrue(placed == InteractionResult.SUCCESS, "igloo placer succeeded");
				check(server, s -> alice(s).getMainHandItem().isEmpty(), "placer was consumed");

				// ---- config UIs
				if (!vanillaHud) {
					server.runCommand("execute as Alice run ds config");
					ctx.waitForScreen(ConfigScreen.class);
					ctx.takeScreenshot(tag + "04_config_screen");
					ctx.setScreen(() -> null);
				}
				server.runCommand("execute as Alice run ds config menu");
				ctx.waitForScreen(ContainerScreen.class);
				ctx.takeScreenshot(tag + "05_config_menu");
				ctx.setScreen(() -> null);
				ctx.waitTicks(5);

				// ---- round ends while a player is on the death screen
				server.runCommand("kill Alice");
				ctx.waitForScreen(DeathScreen.class);
				ctx.takeScreenshot(tag + "06_dead");
				server.runCommand("ds endround");
				server.waitFor(s -> game().state() == GameManager.State.INTERMISSION, MINUTE);
				ctx.waitFor(mc -> !(mc.gui.screen() instanceof DeathScreen), 10 * 20);
				check(server, s -> alice(s).isAlive() && alice(s).level() == s.overworld(), "dead player was respawned into the lobby");
				server.waitFor(s -> alice(s).connection.hasClientLoaded(), 10 * 20);
				LOG.info("ok: force-respawned player is fully loaded again");
				conn.waitForChunksRender();
				ctx.waitTicks(20);
				ctx.takeScreenshot(tag + "07_intermission");

				// ---- round 2, then game over
				server.waitFor(s -> game().state() == GameManager.State.RUNNING, MINUTE);
				check(server, s -> game().round() == 2 && RoundWorld.active().contains(alice(s).level()), "round 2 started in a new world");
				conn.waitForChunksRender();
				ctx.waitTicks(20);
				ctx.takeScreenshot(tag + "08_round2");
				server.runCommand("ds endround");
				server.waitFor(s -> game().state() == GameManager.State.LOBBY, MINUTE);
				ctx.waitTicks(40);
				check(server, s -> alice(s).level() == s.overworld() && bob(s).level() == s.overworld(), "everyone is back in the lobby");
				check(server, s -> s.getAllLevels() instanceof Iterable<ServerLevel> levels
						&& java.util.stream.StreamSupport.stream(levels.spliterator(), false)
						.noneMatch(l -> l.dimension().identifier().getNamespace().equals("deathswap")), "round worlds were unloaded");
				ctx.takeScreenshot(tag + "09_game_over");
				LOG.info("ALL DEATHSWAP CHECKS PASSED ({})", vanillaHud ? "pack HUD" : "mod HUD");
			}
		}
	}

	private static GameManager game() {
		return GameManager.get();
	}

	private static ServerPlayer alice(MinecraftServer s) {
		return s.getPlayerList().getPlayerByName("Alice");
	}

	private static ServerPlayer bob(MinecraftServer s) {
		return s.getPlayerList().getPlayerByName("Bob");
	}

	private static PlayerState stats(MinecraftServer s, String name) {
		return game().players().get(s.getPlayerList().getPlayerByName(name).getUUID());
	}

	private static void check(TestDedicatedServerContext server, java.util.function.Predicate<MinecraftServer> condition, String what) {
		assertTrue(server.computeOnServer(condition::test), what);
	}

	private static void assertTrue(boolean ok, String what) {
		if (!ok) throw new AssertionError("FAILED: " + what);
		LOG.info("ok: {}", what);
	}
}
