package io.github.babygipsn.deathswap.hud;

import io.github.babygipsn.deathswap.config.ConfigManager;
import io.github.babygipsn.deathswap.config.DeathswapConfig;
import io.github.babygipsn.deathswap.config.PlayerPrefs;
import io.github.babygipsn.deathswap.game.Fx;
import io.github.babygipsn.deathswap.game.GameManager;
import io.github.babygipsn.deathswap.game.PlayerState;
import io.github.babygipsn.deathswap.lang.Lang;
import io.github.babygipsn.deathswap.net.DeathswapNetworking.HudPayload;
import io.github.babygipsn.deathswap.pack.ResourcePackManager;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Shows the timer and death list. Per player, the best available channel is used:
 * <ol>
 *   <li>client has the mod: lines are sent as a payload and drawn natively at the left screen edge</li>
 *   <li>vanilla client with the resource pack: lines are encoded into an invisible boss bar's title</li>
 *   <li>otherwise: a compact one-line summary in the action bar</li>
 * </ol>
 */
public final class HudManager {
	public static final int RED = 0xFF5555;
	public static final int GOLD = 0xFFAA00;
	public static final int YELLOW = 0xFFFF55;
	public static final int WHITE = 0xFFFFFF;
	public static final int GRAY = 0xAAAAAA;
	public static final int DARK_GRAY = 0x777777;
	public static final int GREEN = 0x55FF55;
	private static final int VALUE_COLUMN = 52;

	public enum Mode { MOD, PACK, ACTIONBAR, HIDDEN }

	private static final Map<UUID, ServerBossEvent> BARS = new HashMap<>();
	private static final Map<UUID, Object> LAST_SENT = new HashMap<>();

	private HudManager() {
	}

	public static Mode mode(ServerPlayer p) {
		if (!ConfigManager.get().hud.enabled || PlayerPrefs.get(p.getUUID()).hudHidden) return Mode.HIDDEN;
		if (ServerPlayNetworking.canSend(p, HudPayload.TYPE)) return Mode.MOD;
		if (ResourcePackManager.status(p) == ResourcePackManager.Status.LOADED) return Mode.PACK;
		return Mode.ACTIONBAR;
	}

	/** Called every 10 ticks. */
	public static void update(GameManager game) {
		boolean actionbarTick = game.tickCount() % 20 == 0;
		for (ServerPlayer p : game.server.getPlayerList().getPlayers()) {
			Mode mode = mode(p);
			if (mode != Mode.PACK) removeBar(p);
			switch (mode) {
				case MOD -> {
					List<HudLine> lines = build(game, p);
					if (!lines.equals(LAST_SENT.get(p.getUUID()))) {
						ServerPlayNetworking.send(p, new HudPayload(lines));
						LAST_SENT.put(p.getUUID(), lines);
					}
				}
				case PACK -> {
					Component text = HudFont.encode(build(game, p), PlayerPrefs.hudX(p.getUUID()), PlayerPrefs.hudY(p.getUUID()));
					ServerBossEvent bar = BARS.computeIfAbsent(p.getUUID(), id -> {
						ServerBossEvent b = new ServerBossEvent(UUID.randomUUID(), text, BossEvent.BossBarColor.WHITE, BossEvent.BossBarOverlay.PROGRESS);
						b.setProgress(0f);
						b.addPlayer(p);
						return b;
					});
					if (!Objects.equals(LAST_SENT.get(p.getUUID()), text)) {
						bar.setName(text);
						LAST_SENT.put(p.getUUID(), text);
					}
				}
				case ACTIONBAR -> {
					if (actionbarTick) p.sendOverlayMessage(Component.literal(compact(game, p)));
				}
				case HIDDEN -> {
					if (ServerPlayNetworking.canSend(p, HudPayload.TYPE) && !List.of().equals(LAST_SENT.get(p.getUUID()))) {
						ServerPlayNetworking.send(p, new HudPayload(List.of()));
						LAST_SENT.put(p.getUUID(), List.of());
					}
				}
			}
		}
	}

	public static void onLeave(ServerPlayer p) {
		removeBar(p);
		LAST_SENT.remove(p.getUUID());
	}

	/** Forces a full resend (e.g. after the player moved their HUD). */
	public static void invalidate(ServerPlayer p) {
		LAST_SENT.remove(p.getUUID());
	}

	private static void removeBar(ServerPlayer p) {
		ServerBossEvent bar = BARS.remove(p.getUUID());
		if (bar != null) {
			bar.removeAllPlayers();
			LAST_SENT.remove(p.getUUID());
		}
	}

	// ------------------------------------------------------------------ content

	public static List<HudLine> build(GameManager game, ServerPlayer viewer) {
		DeathswapConfig cfg = ConfigManager.get();
		List<HudLine> lines = new ArrayList<>();
		lines.add(HudLine.builder().bold("DEATHSWAP", RED).build());
		switch (game.state()) {
			case LOBBY, STARTING -> {
				lines.add(HudLine.builder().text(Lang.str(viewer, "deathswap.hud.lobby"), GRAY).build());
				lines.add(HudLine.builder().text(Lang.str(viewer, "deathswap.hud.ready"), GRAY)
						.at(VALUE_COLUMN, game.readyCount() + "/" + game.lobbyCandidates().size(), WHITE).build());
				if (game.state() == GameManager.State.STARTING) {
					lines.add(HudLine.builder().text(Lang.str(viewer, "deathswap.hud.starting"), GRAY)
							.at(VALUE_COLUMN, Fx.time(game.countdown()), YELLOW).build());
				} else if (game.isOptedOut(viewer.getUUID())) {
					lines.add(HudLine.builder().text(Lang.str(viewer, "deathswap.hud.opted_out"), DARK_GRAY).build());
				} else if (!game.isReady(viewer.getUUID())) {
					lines.add(HudLine.builder().text(Lang.str(viewer, "deathswap.hud.hint_ready"), YELLOW).build());
				}
				lines.add(HudLine.empty());
				int shown = 0;
				for (ServerPlayer p : game.lobbyCandidates()) {
					if (shown++ >= cfg.hud.maxPlayersShown) break;
					boolean ready = game.isReady(p.getUUID());
					lines.add(HudLine.builder().text(ready ? "+ " : "- ", ready ? GREEN : DARK_GRAY)
							.text(p.getName().getString(), p == viewer ? YELLOW : (ready ? WHITE : GRAY)).build());
				}
			}
			case RUNNING -> {
				lines.add(HudLine.builder().text(Lang.str(viewer, "deathswap.hud.round"), GRAY)
						.at(VALUE_COLUMN, game.round() + "/" + game.totalRounds(), WHITE).build());
				int swapTicks = game.swapTicksLeft();
				boolean swapsLeft = swapTicks < game.roundTicksLeft();
				boolean warn = swapTicks <= cfg.game.warningSeconds * 20;
				String swapValue = !swapsLeft ? "--:--"
						: (cfg.game.timerMode == DeathswapConfig.TimerMode.HIDDEN && !warn) ? "??:??" : Fx.time(swapTicks);
				lines.add(HudLine.builder().text(Lang.str(viewer, "deathswap.hud.swap"), GRAY)
						.at(VALUE_COLUMN, swapValue, warn && swapsLeft ? RED : WHITE).build());
				lines.add(HudLine.builder().text(Lang.str(viewer, "deathswap.hud.ends"), GRAY)
						.at(VALUE_COLUMN, Fx.time(game.roundTicksLeft()), WHITE).build());
				if (!game.isParticipant(viewer)) {
					lines.add(HudLine.builder().text(Lang.str(viewer, "deathswap.hud.spectating"), DARK_GRAY).build());
				}
				lines.add(HudLine.empty());
				addLeaderboard(game, viewer, lines, cfg.hud.maxPlayersShown);
			}
			case INTERMISSION -> {
				lines.add(HudLine.builder().text(Lang.str(viewer, "deathswap.hud.round"), GRAY)
						.at(VALUE_COLUMN, game.round() + "/" + game.totalRounds(), WHITE).build());
				lines.add(HudLine.builder().text(Lang.str(viewer, "deathswap.hud.next_round"), GRAY)
						.at(VALUE_COLUMN, Fx.time(game.countdown()), YELLOW).build());
				lines.add(HudLine.empty());
				addLeaderboard(game, viewer, lines, cfg.hud.maxPlayersShown);
			}
		}
		return lines;
	}

	private static void addLeaderboard(GameManager game, ServerPlayer viewer, List<HudLine> lines, int max) {
		lines.add(HudLine.builder().text(Lang.str(viewer, "deathswap.hud.deaths"), GOLD).build());
		List<PlayerState> ranking = game.ranking();
		int viewerIndex = -1;
		for (int i = 0; i < ranking.size(); i++) {
			if (ranking.get(i).id.equals(viewer.getUUID())) viewerIndex = i;
		}
		List<Integer> shown = new ArrayList<>();
		for (int i = 0; i < ranking.size() && i < max; i++) shown.add(i);
		if (viewerIndex >= max) shown.add(viewerIndex);
		// the death count column sits just right of the widest "N. name"
		int column = VALUE_COLUMN;
		for (int i : shown) {
			column = Math.max(column, HudFont.width(HudFont.sanitize(rankPrefix(i) + shortName(ranking.get(i))), false) + 8);
		}
		for (int i : shown) {
			if (i == viewerIndex && viewerIndex >= max) lines.add(HudLine.builder().text("...", DARK_GRAY).build());
			lines.add(rankLine(game, ranking.get(i), i, i == viewerIndex, column));
		}
	}

	private static String rankPrefix(int index) {
		return (index + 1) + ". ";
	}

	private static String shortName(PlayerState ps) {
		return ps.name.length() > 14 ? ps.name.substring(0, 13) + "." : ps.name;
	}

	private static HudLine rankLine(GameManager game, PlayerState ps, int index, boolean self, int column) {
		boolean online = game.server.getPlayerList().getPlayer(ps.id) != null;
		return HudLine.builder()
				.text(rankPrefix(index), DARK_GRAY)
				.text(shortName(ps), self ? YELLOW : (online ? WHITE : DARK_GRAY))
				.at(column, String.valueOf(ps.deaths), RED)
				.build();
	}

	/** One-line fallback for clients without the mod or the pack. */
	private static String compact(GameManager game, ServerPlayer viewer) {
		DeathswapConfig cfg = ConfigManager.get();
		return switch (game.state()) {
			case LOBBY -> Lang.str(viewer, "deathswap.hud.compact_lobby", game.readyCount(), game.lobbyCandidates().size());
			case STARTING -> Lang.str(viewer, "deathswap.hud.compact_starting", Fx.time(game.countdown()));
			case INTERMISSION -> Lang.str(viewer, "deathswap.hud.compact_intermission", Fx.time(game.countdown()));
			case RUNNING -> {
				PlayerState ps = game.players().get(viewer.getUUID());
				boolean hide = cfg.game.timerMode == DeathswapConfig.TimerMode.HIDDEN && game.swapTicksLeft() > cfg.game.warningSeconds * 20;
				yield Lang.str(viewer, "deathswap.hud.compact_running",
						hide ? "??:??" : Fx.time(game.swapTicksLeft()), game.round(), game.totalRounds(), ps != null ? ps.deaths : 0);
			}
		};
	}
}
