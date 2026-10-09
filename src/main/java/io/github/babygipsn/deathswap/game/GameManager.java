package io.github.babygipsn.deathswap.game;

import io.github.babygipsn.deathswap.Deathswap;
import io.github.babygipsn.deathswap.config.ConfigManager;
import io.github.babygipsn.deathswap.config.DeathswapConfig;
import io.github.babygipsn.deathswap.feature.RandomEvents;
import io.github.babygipsn.deathswap.feature.RandomItems;
import io.github.babygipsn.deathswap.feature.StartKit;
import io.github.babygipsn.deathswap.lang.Lang;
import io.github.babygipsn.deathswap.mixin.ServerGamePacketListenerImplAccessor;
import io.github.babygipsn.deathswap.world.RoundWorld;
import io.github.babygipsn.deathswap.world.SpawnFinder;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Deathswap state machine: lobby -> countdown -> rounds (fresh world each) -> results -> lobby.
 * Everything runs on the server thread, driven by {@link #tick()}.
 */
public final class GameManager {
	/** Players inside a round world carry this tag, so a rejoin after the world is gone sends them to the lobby. */
	public static final String IN_ROUND_TAG = "deathswap.in_round";

	public enum State { LOBBY, STARTING, RUNNING, INTERMISSION }

	private static @Nullable GameManager instance;

	public final MinecraftServer server;
	private State state = State.LOBBY;
	private long tick;
	private int countdown;
	private boolean forcedStart;

	private int round;
	private int totalRounds;
	private int roundTicksLeft;
	private int swapTicksLeft;
	private int itemTicksLeft;
	private int eventTicksLeft;
	private @Nullable RoundWorld world;

	private final Map<UUID, PlayerState> players = new LinkedHashMap<>();
	private final Set<UUID> ready = new HashSet<>();
	private final Set<UUID> optOut = new HashSet<>();
	private final Set<UUID> pendingJoin = new HashSet<>();

	private GameManager(MinecraftServer server) {
		this.server = server;
	}

	public static GameManager get() {
		if (instance == null) throw new IllegalStateException("Deathswap is not running on a server");
		return instance;
	}

	public static @Nullable GameManager getOrNull() {
		return instance;
	}

	public static void onServerStarted(MinecraftServer server) {
		instance = new GameManager(server);
	}

	public static void onServerStopping() {
		if (instance != null && instance.world != null) {
			instance.world.delete();
		}
		instance = null;
		RoundWorld.setActive(null);
	}

	// ------------------------------------------------------------------ accessors

	public State state() {
		return this.state;
	}

	public long tickCount() {
		return this.tick;
	}

	public int round() {
		return this.round;
	}

	public int totalRounds() {
		return this.totalRounds;
	}

	public int roundTicksLeft() {
		return this.roundTicksLeft;
	}

	public int swapTicksLeft() {
		return this.swapTicksLeft;
	}

	public int countdown() {
		return this.countdown;
	}

	public @Nullable RoundWorld world() {
		return this.world;
	}

	public Map<UUID, PlayerState> players() {
		return this.players;
	}

	public boolean isParticipant(ServerPlayer player) {
		return this.players.containsKey(player.getUUID());
	}

	public boolean isReady(UUID id) {
		return this.ready.contains(id);
	}

	public boolean isOptedOut(UUID id) {
		return this.optOut.contains(id);
	}

	public boolean isInGame() {
		return this.state == State.RUNNING || this.state == State.INTERMISSION;
	}

	/** Online players who would take part if the game started now. */
	public List<ServerPlayer> lobbyCandidates() {
		return this.server.getPlayerList().getPlayers().stream().filter(p -> !this.optOut.contains(p.getUUID())).toList();
	}

	public long readyCount() {
		return this.lobbyCandidates().stream().filter(p -> this.ready.contains(p.getUUID())).count();
	}

	/** Participants sorted for the leaderboard: fewest deaths first, then (optionally) most trap kills. */
	public List<PlayerState> ranking() {
		List<PlayerState> list = new ArrayList<>(this.players.values());
		Comparator<PlayerState> cmp = Comparator.comparingInt(ps -> ps.deaths);
		if (ConfigManager.get().game.tiebreaker == DeathswapConfig.Tiebreaker.TRAP_KILLS) {
			cmp = cmp.thenComparing(Comparator.comparingInt((PlayerState ps) -> ps.trapKills).reversed());
		}
		list.sort(cmp.thenComparing(ps -> ps.name.toLowerCase()));
		return list;
	}

	// ------------------------------------------------------------------ lobby actions

	public void toggleReady(ServerPlayer player) {
		if (this.state != State.LOBBY && this.state != State.STARTING) {
			Fx.msg(player, "deathswap.msg.ready_ingame");
			return;
		}
		UUID id = player.getUUID();
		if (this.ready.remove(id)) {
			Fx.broadcast(this.server, "deathswap.msg.unready", player.getName().getString());
		} else {
			this.optOut.remove(id);
			this.ready.add(id);
			Fx.broadcast(this.server, "deathswap.msg.ready", player.getName().getString(), this.readyCount(), this.lobbyCandidates().size());
		}
	}

	public void toggleOptOut(ServerPlayer player) {
		UUID id = player.getUUID();
		if (this.optOut.remove(id)) {
			Fx.msg(player, "deathswap.msg.optin");
		} else {
			this.optOut.add(id);
			this.ready.remove(id);
			this.pendingJoin.remove(id);
			Fx.msg(player, "deathswap.msg.optout");
		}
	}

	/** Starts the countdown (forced = ignore ready checks / minimum players). Returns an error key or null. */
	public @Nullable String requestStart(boolean force) {
		if (this.state != State.LOBBY) return "deathswap.err.already_running";
		int candidates = this.lobbyCandidates().size();
		if (candidates < 1) return "deathswap.err.no_players";
		if (!force && candidates < ConfigManager.get().lobby.minPlayers) return "deathswap.err.not_enough_players";
		this.forcedStart = force;
		this.state = State.STARTING;
		this.countdown = ConfigManager.get().lobby.startCountdownSeconds * 20;
		Fx.broadcast(this.server, "deathswap.msg.starting", ConfigManager.get().lobby.startCountdownSeconds);
		return null;
	}

	public void stop(boolean announce) {
		if (this.state == State.LOBBY) return;
		this.state = State.LOBBY;
		if (this.world != null) {
			this.evacuate();
			this.world.delete();
			this.world = null;
		}
		this.ready.clear();
		this.pendingJoin.clear();
		if (announce) Fx.broadcast(this.server, "deathswap.msg.stopped");
	}

	// ------------------------------------------------------------------ main loop

	public void tick() {
		this.tick++;
		DeathswapConfig cfg = ConfigManager.get();
		switch (this.state) {
			case LOBBY -> {
				if (cfg.lobby.autoStart && this.canAutoStart()) this.requestStart(false);
				if (this.tick % 20 == 0 && cfg.lobby.protectLobby) this.feedLobby();
			}
			case STARTING -> this.tickStarting(cfg);
			case RUNNING -> this.tickRound(cfg);
			case INTERMISSION -> {
				if (--this.countdown <= 0) this.beginRound();
				if (this.tick % 20 == 0 && cfg.lobby.protectLobby) this.feedLobby();
			}
		}
	}

	private boolean canAutoStart() {
		List<ServerPlayer> candidates = this.lobbyCandidates();
		return candidates.size() >= Math.max(1, ConfigManager.get().lobby.minPlayers)
				&& candidates.stream().allMatch(p -> this.ready.contains(p.getUUID()));
	}

	private void tickStarting(DeathswapConfig cfg) {
		if (!this.forcedStart && !this.canAutoStart()) {
			this.state = State.LOBBY;
			Fx.broadcast(this.server, "deathswap.msg.start_cancelled");
			return;
		}
		if (this.countdown % 20 == 0) {
			int seconds = this.countdown / 20;
			if (seconds > 0 && (seconds <= 5 || seconds % 10 == 0)) {
				for (ServerPlayer p : this.server.getPlayerList().getPlayers()) {
					Fx.title(p, Component.literal(String.valueOf(seconds)).withStyle(ChatFormatting.RED),
							Lang.tr(p, "deathswap.title.starting"), 0, 25, 5);
					Fx.sound(p, SoundEvents.NOTE_BLOCK_HAT, 1f, 1.2f);
				}
			}
		}
		if (--this.countdown <= 0) this.startGame();
	}

	private void startGame() {
		DeathswapConfig cfg = ConfigManager.get();
		this.players.clear();
		this.pendingJoin.clear();
		for (ServerPlayer p : this.lobbyCandidates()) {
			this.players.put(p.getUUID(), new PlayerState(p.getUUID(), p.getName().getString()));
		}
		if (this.players.isEmpty()) {
			this.state = State.LOBBY;
			return;
		}
		this.totalRounds = cfg.game.rounds;
		this.round = 0;
		this.beginRound();
	}

	private void beginRound() {
		DeathswapConfig cfg = ConfigManager.get();
		for (UUID id : this.pendingJoin) {
			ServerPlayer p = this.server.getPlayerList().getPlayer(id);
			if (p != null) this.players.putIfAbsent(id, new PlayerState(id, p.getName().getString()));
		}
		this.pendingJoin.clear();
		this.round++;
		for (ServerPlayer p : this.server.getPlayerList().getPlayers()) {
			Fx.title(p, Lang.tr(p, "deathswap.title.round", this.round, this.totalRounds).withStyle(ChatFormatting.RED),
					Lang.tr(p, "deathswap.title.generating"), 0, 100, 10);
		}

		long started = System.currentTimeMillis();
		this.world = RoundWorld.create(this.server, cfg, this.round);
		RoundWorld.setActive(this.world);
		ServerLevel level = this.world.overworld();

		List<ServerPlayer> online = new ArrayList<>();
		for (PlayerState ps : this.players.values()) {
			ps.roundDeaths = 0;
			ps.trapSetter = null;
			ps.lastSwapTick = Long.MIN_VALUE / 2;
			ServerPlayer p = this.server.getPlayerList().getPlayer(ps.id);
			if (p != null) online.add(p);
		}
		List<BlockPos> spawns = SpawnFinder.spread(level, Math.max(1, online.size()), cfg.world.spreadRadius, cfg.world.minPlayerDistance);
		for (int i = 0; i < online.size(); i++) {
			ServerPlayer p = online.get(i);
			PlayerState ps = this.players.get(p.getUUID());
			ps.spawn = spawns.get(i);
			this.enterRound(p, ps.spawn);
		}
		// everyone else watches
		for (ServerPlayer p : this.server.getPlayerList().getPlayers()) {
			if (!this.players.containsKey(p.getUUID())) this.enterAsSpectator(p);
		}
		Deathswap.LOGGER.info("Round {} prepared in {} ms", this.round, System.currentTimeMillis() - started);

		this.roundTicksLeft = cfg.game.roundSeconds * 20;
		this.swapTicksLeft = cfg.game.swapIntervalSeconds * 20;
		this.itemTicksLeft = cfg.randomItems.intervalSeconds * 20;
		this.eventTicksLeft = cfg.events.intervalSeconds * 20;
		this.state = State.RUNNING;
		for (ServerPlayer p : this.server.getPlayerList().getPlayers()) {
			Fx.title(p, Lang.tr(p, "deathswap.title.round", this.round, this.totalRounds).withStyle(ChatFormatting.RED),
					Lang.tr(p, "deathswap.title.round_sub"), 5, 50, 15);
			Fx.sound(p, SoundEvents.WITHER_SPAWN, 0.5f, 1.4f);
		}
	}

	private void tickRound(DeathswapConfig cfg) {
		this.roundTicksLeft--;
		this.swapTicksLeft--;

		if (this.roundTicksLeft <= 0) {
			this.endRound();
			return;
		}

		boolean swapThisRound = this.swapTicksLeft < this.roundTicksLeft;
		if (swapThisRound && this.swapTicksLeft > 0 && this.swapTicksLeft % 20 == 0) {
			int seconds = this.swapTicksLeft / 20;
			if (seconds == cfg.game.warningSeconds && seconds > cfg.game.countdownTitleSeconds) {
				for (ServerPlayer p : this.roundPlayers()) {
					Fx.msg(p, "deathswap.msg.swap_warning", seconds);
					Fx.sound(p, SoundEvents.NOTE_BLOCK_PLING, 0.8f, 0.8f);
				}
			}
			if (seconds <= cfg.game.countdownTitleSeconds) {
				for (ServerPlayer p : this.roundPlayers()) {
					Fx.title(p, Component.literal(String.valueOf(seconds)).withStyle(ChatFormatting.GOLD),
							Lang.tr(p, "deathswap.title.swap_in"), 0, 22, 2);
					Fx.sound(p, SoundEvents.NOTE_BLOCK_PLING, 1f, 0.6f + (cfg.game.countdownTitleSeconds - seconds) * 0.15f);
				}
			}
		}
		if (this.swapTicksLeft <= 0) {
			this.swap();
			this.swapTicksLeft = cfg.game.swapIntervalSeconds * 20;
		}

		if (cfg.randomItems.enabled && --this.itemTicksLeft <= 0) {
			this.itemTicksLeft = cfg.randomItems.intervalSeconds * 20;
			for (ServerPlayer p : this.activeParticipants()) RandomItems.give(p);
		}
		if (cfg.events.enabled && --this.eventTicksLeft <= 0) {
			this.eventTicksLeft = cfg.events.intervalSeconds * 20;
			RandomEvents.trigger(this, null);
		}
		if (this.tick % 20 == 0 && this.activeParticipantsOnline() == 0) {
			Deathswap.LOGGER.info("No participants left online, stopping the game");
			this.stop(true);
		}
	}

	private void endRound() {
		DeathswapConfig cfg = ConfigManager.get();
		boolean last = this.round >= this.totalRounds;
		this.announceRoundResults();
		this.state = State.INTERMISSION; // before evacuating, so respawns go to the lobby
		this.evacuate();
		if (this.world != null) {
			this.world.delete();
			this.world = null;
		}
		if (last) {
			this.endGame();
		} else {
			this.state = State.INTERMISSION;
			this.countdown = cfg.game.intermissionSeconds * 20;
		}
	}

	private void announceRoundResults() {
		for (ServerPlayer viewer : this.server.getPlayerList().getPlayers()) {
			viewer.sendSystemMessage(Fx.prefix().append(Lang.tr(viewer, "deathswap.msg.round_over", this.round, this.totalRounds).withStyle(ChatFormatting.GOLD)));
			for (PlayerState ps : this.ranking()) {
				viewer.sendSystemMessage(Lang.tr(viewer, "deathswap.msg.round_line", ps.name, ps.roundDeaths, ps.deaths, ps.trapKills).withStyle(ChatFormatting.GRAY));
			}
		}
	}

	private void endGame() {
		List<PlayerState> ranking = this.ranking();
		List<PlayerState> winners = new ArrayList<>();
		for (PlayerState ps : ranking) {
			if (ps.deaths == ranking.getFirst().deaths
					&& (ConfigManager.get().game.tiebreaker == DeathswapConfig.Tiebreaker.NONE || ps.trapKills == ranking.getFirst().trapKills)) {
				winners.add(ps);
			}
		}
		String winnerNames = String.join(", ", winners.stream().map(ps -> ps.name).toList());
		PlayerState bestTrapper = this.players.values().stream().max(Comparator.comparingInt(ps -> ps.trapKills)).orElse(null);

		for (ServerPlayer p : this.server.getPlayerList().getPlayers()) {
			p.sendSystemMessage(Fx.prefix().append(Lang.tr(p, "deathswap.msg.game_over").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
			int place = 1;
			for (PlayerState ps : ranking) {
				p.sendSystemMessage(Component.literal(" " + place++ + ". ").withStyle(ChatFormatting.YELLOW)
						.append(Lang.tr(p, "deathswap.msg.final_line", ps.name, ps.deaths, ps.trapKills, this.causeSummary(ps)).withStyle(ChatFormatting.GRAY)));
			}
			if (bestTrapper != null && bestTrapper.trapKills > 0) {
				Fx.msg(p, "deathswap.msg.best_trapper", bestTrapper.name, bestTrapper.trapKills);
			}
			Fx.title(p, Lang.tr(p, winners.size() > 1 ? "deathswap.title.winners" : "deathswap.title.winner").withStyle(ChatFormatting.GOLD),
					Component.literal(winnerNames).withStyle(ChatFormatting.YELLOW), 10, 100, 20);
			Fx.sound(p, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
		}
		this.state = State.LOBBY;
		this.ready.clear();
	}

	public String causeSummary(PlayerState ps) {
		if (ps.causes.isEmpty()) return "-";
		List<String> parts = new ArrayList<>();
		ps.causes.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
				.forEach(e -> parts.add(e.getKey() + " x" + e.getValue()));
		return String.join(", ", parts);
	}

	// ------------------------------------------------------------------ swapping

	/** Swaps random pairs of living participants; with an odd count the last three rotate. */
	public void swap() {
		List<ServerPlayer> pool = new ArrayList<>(this.activeParticipants());
		pool.removeIf(p -> p.isDeadOrDying() || p.isSpectator());
		if (pool.size() < 2) {
			for (ServerPlayer p : pool) Fx.msg(p, "deathswap.msg.swap_skipped");
			return;
		}
		Collections.shuffle(pool);

		// destination[i] = where pool[i] goes
		Map<ServerPlayer, ServerPlayer> moveTo = new LinkedHashMap<>();
		int pairEnd = pool.size() % 2 == 1 ? pool.size() - 3 : pool.size();
		for (int i = 0; i < pairEnd; i += 2) {
			moveTo.put(pool.get(i), pool.get(i + 1));
			moveTo.put(pool.get(i + 1), pool.get(i));
		}
		if (pool.size() % 2 == 1) {
			ServerPlayer a = pool.get(pool.size() - 3), b = pool.get(pool.size() - 2), c = pool.get(pool.size() - 1);
			moveTo.put(a, b);
			moveTo.put(b, c);
			moveTo.put(c, a);
		}

		Map<ServerPlayer, Snapshot> snapshots = new LinkedHashMap<>();
		for (ServerPlayer p : pool) snapshots.put(p, Snapshot.of(p));

		DeathswapConfig cfg = ConfigManager.get();
		for (Map.Entry<ServerPlayer, ServerPlayer> e : moveTo.entrySet()) {
			ServerPlayer mover = e.getKey();
			ServerPlayer target = e.getValue();
			Snapshot from = snapshots.get(mover);
			Snapshot to = snapshots.get(target);
			mover.stopRiding();
			mover.teleport(new TeleportTransition(to.level, to.pos, to.motion, mover.getYRot(), mover.getXRot(), TeleportTransition.DO_NOTHING));
			mover.fallDistance = to.fallDistance;

			PlayerState ps = this.players.get(mover.getUUID());
			ps.trapSetter = target.getUUID();
			ps.lastSwapTick = this.tick;
			ps.graceUntilTick = this.tick + cfg.game.graceSeconds * 20L;

			if (cfg.game.swapEffects) {
				from.level.sendParticles(ParticleTypes.PORTAL, from.pos.x, from.pos.y + 1, from.pos.z, 60, 0.4, 0.8, 0.4, 0.2);
				to.level.sendParticles(ParticleTypes.REVERSE_PORTAL, to.pos.x, to.pos.y + 1, to.pos.z, 60, 0.4, 0.8, 0.4, 0.05);
				Fx.sound(mover, SoundEvents.ENDERMAN_TELEPORT, 1f, 1f);
			}
			Fx.title(mover, Lang.tr(mover, "deathswap.title.swapped").withStyle(ChatFormatting.LIGHT_PURPLE),
					Lang.tr(mover, "deathswap.title.swapped_sub", target.getName().getString()), 0, 30, 10);
		}
	}

	private record Snapshot(ServerLevel level, Vec3 pos, Vec3 motion, double fallDistance) {
		static Snapshot of(ServerPlayer p) {
			return new Snapshot(p.level(), p.position(), p.getDeltaMovement(), p.fallDistance);
		}
	}

	// ------------------------------------------------------------------ player lifecycle

	private void enterRound(ServerPlayer p, BlockPos spawn) {
		p.stopRiding();
		resetPlayer(p);
		p.setGameMode(GameType.SURVIVAL);
		p.setRespawnPosition(null, false);
		p.teleport(new TeleportTransition(this.world.overworld(), Vec3.atBottomCenterOf(spawn), Vec3.ZERO, p.getYRot(), 0, TeleportTransition.DO_NOTHING));
		p.addTag(IN_ROUND_TAG);
		StartKit.give(p);
	}

	private void enterAsSpectator(ServerPlayer p) {
		if (this.world == null) return;
		p.stopRiding();
		p.setGameMode(GameType.SPECTATOR);
		BlockPos center = this.players.values().stream().map(ps -> ps.spawn).filter(java.util.Objects::nonNull).findFirst().orElse(BlockPos.ZERO.above(100));
		p.teleport(new TeleportTransition(this.world.overworld(), Vec3.atBottomCenterOf(center).add(0, 10, 0), Vec3.ZERO, p.getYRot(), p.getXRot(), TeleportTransition.DO_NOTHING));
		p.addTag(IN_ROUND_TAG);
	}

	public void sendToLobby(ServerPlayer p) {
		ServerLevel lobby = this.server.findRespawnDimension();
		var spawn = this.server.getRespawnData();
		BlockPos pos = p.adjustSpawnLocation(lobby, spawn.pos());
		p.stopRiding();
		resetPlayer(p);
		p.setRespawnPosition(null, false);
		p.teleport(new TeleportTransition(lobby, Vec3.atBottomCenterOf(pos), Vec3.ZERO, spawn.yaw(), spawn.pitch(), TeleportTransition.DO_NOTHING));
		p.setGameMode(switch (ConfigManager.get().lobby.lobbyGameMode) {
			case ADVENTURE -> GameType.ADVENTURE;
			case SURVIVAL -> GameType.SURVIVAL;
			case CREATIVE -> GameType.CREATIVE;
			case SPECTATOR -> GameType.SPECTATOR;
		});
		p.removeTag(IN_ROUND_TAG);
	}

	/** Moves everyone out of the round world. Players on the death screen are respawned first (dead players can't be teleported). */
	private void evacuate() {
		for (ServerPlayer p : List.copyOf(this.server.getPlayerList().getPlayers())) {
			boolean inRound = this.world != null && this.world.contains(p.level()) || p.entityTags().contains(IN_ROUND_TAG);
			if (!inRound) continue;
			if (p.isDeadOrDying()) {
				p = this.server.getPlayerList().respawn(p, false, Entity.RemovalReason.KILLED);
				p.connection.player = p;
				p.connection.resetPosition();
				((ServerGamePacketListenerImplAccessor) p.connection).deathswap$restartClientLoadTimerAfterRespawn();
			}
			this.sendToLobby(p);
		}
	}

	private static void resetPlayer(ServerPlayer p) {
		p.getInventory().clearContent();
		p.removeAllEffects();
		p.setHealth(p.getMaxHealth());
		p.getFoodData().setFoodLevel(20);
		p.getFoodData().setSaturation(5f);
		p.setExperienceLevels(0);
		p.setExperiencePoints(0);
		p.clearFire();
		p.resetFallDistance();
		p.setDeltaMovement(Vec3.ZERO);
	}

	private void feedLobby() {
		for (ServerPlayer p : this.server.getPlayerList().getPlayers()) {
			if (this.isInLobby(p)) {
				p.getFoodData().setFoodLevel(20);
			}
		}
	}

	public boolean isInLobby(ServerPlayer p) {
		return this.world == null || !this.world.contains(p.level());
	}

	public void onJoin(ServerPlayer p) {
		PlayerState ps = this.players.get(p.getUUID());
		if (ps != null) ps.name = p.getName().getString();
		boolean tagged = p.entityTags().contains(IN_ROUND_TAG);

		if (this.state == State.RUNNING && this.world != null) {
			if (ps != null) {
				if (!this.world.contains(p.level())) {
					// came back after the world they logged out in was replaced
					this.enterRound(p, ps.spawn != null ? ps.spawn : BlockPos.ZERO);
				}
			} else if (ConfigManager.get().game.lateJoin == DeathswapConfig.LateJoin.NEXT_ROUND && !this.optOut.contains(p.getUUID())) {
				this.pendingJoin.add(p.getUUID());
				if (tagged || !this.isInLobby(p)) this.sendToLobby(p);
				Fx.msg(p, "deathswap.msg.join_next_round");
			} else {
				this.enterAsSpectator(p);
				Fx.msg(p, "deathswap.msg.join_spectate");
			}
			return;
		}
		if (this.state == State.INTERMISSION && ps == null && !this.optOut.contains(p.getUUID())) {
			this.pendingJoin.add(p.getUUID());
		}
		if (tagged) this.sendToLobby(p);
	}

	public void onLeave(ServerPlayer p) {
		this.ready.remove(p.getUUID());
	}

	public void onDeath(ServerPlayer p, DamageSource source) {
		if (this.state != State.RUNNING || this.world == null || !this.world.contains(p.level())) return;
		PlayerState ps = this.players.get(p.getUUID());
		if (ps == null) return;
		ps.deaths++;
		ps.roundDeaths++;
		String cause = source.typeHolder().unwrapKey().map(k -> k.identifier().getPath()).orElse("generic");
		ps.causes.merge(cause, 1, Integer::sum);

		int window = ConfigManager.get().game.trapCreditSeconds * 20;
		if (ps.trapSetter != null && this.tick - ps.lastSwapTick <= window) {
			PlayerState setter = this.players.get(ps.trapSetter);
			if (setter != null && !setter.id.equals(ps.id)) {
				setter.trapKills++;
				Fx.broadcast(this.server, "deathswap.msg.trap_kill", ps.name, setter.name);
			}
		}
	}

	public void afterRespawn(ServerPlayer p) {
		if (this.state == State.RUNNING && this.world != null && this.players.containsKey(p.getUUID())) {
			p.setGameMode(GameType.SURVIVAL);
			p.addTag(IN_ROUND_TAG);
			if (!ConfigManager.get().game.keepInventory) StartKit.give(p);
		} else if (p.entityTags().contains(IN_ROUND_TAG)) {
			this.sendToLobby(p);
		}
	}

	/** Called from the respawn mixin; returns where a dead participant should respawn, or null for vanilla. */
	public @Nullable TeleportTransition respawnOverride(ServerPlayer p, TeleportTransition vanilla) {
		if (this.state != State.RUNNING || this.world == null) return null;
		PlayerState ps = this.players.get(p.getUUID());
		if (ps == null) return null;
		DeathswapConfig.RespawnMode mode = ConfigManager.get().game.respawnMode;
		if (mode == DeathswapConfig.RespawnMode.BED_OR_START && this.world.contains(vanilla.newLevel()) && !vanilla.missingRespawnBlock()) {
			return null; // their bed / anchor in this round's world
		}
		ServerLevel level = this.world.overworld();
		BlockPos pos = ps.spawn;
		if (mode == DeathswapConfig.RespawnMode.RANDOM || pos == null) {
			pos = SpawnFinder.find(level, RandomSource.create(), ConfigManager.get().world.spreadRadius, List.of(), 0);
		}
		return new TeleportTransition(level, Vec3.atBottomCenterOf(pos), Vec3.ZERO, p.getYRot(), 0, TeleportTransition.DO_NOTHING);
	}

	/** Damage filter: lobby protection and post-swap grace. */
	public boolean allowDamage(ServerPlayer p) {
		if (this.isInLobby(p)) {
			return !(ConfigManager.get().lobby.protectLobby && p.level() == this.server.findRespawnDimension());
		}
		PlayerState ps = this.players.get(p.getUUID());
		return ps == null || this.tick >= ps.graceUntilTick;
	}

	// ------------------------------------------------------------------ queries

	/** Online participants currently inside the round world. */
	public List<ServerPlayer> activeParticipants() {
		List<ServerPlayer> list = new ArrayList<>();
		if (this.world == null) return list;
		for (PlayerState ps : this.players.values()) {
			ServerPlayer p = this.server.getPlayerList().getPlayer(ps.id);
			if (p != null && this.world.contains(p.level())) list.add(p);
		}
		return list;
	}

	private long activeParticipantsOnline() {
		return this.players.keySet().stream().filter(id -> this.server.getPlayerList().getPlayer(id) != null).count();
	}

	/** Everyone currently in the round world (participants and spectators). */
	public List<ServerPlayer> roundPlayers() {
		if (this.world == null) return List.of();
		return this.server.getPlayerList().getPlayers().stream().filter(p -> this.world.contains(p.level())).toList();
	}

	public void setRoundTicksLeft(int ticks) {
		this.roundTicksLeft = Math.max(1, ticks);
	}

	public void setSwapTicksLeft(int ticks) {
		this.swapTicksLeft = Math.max(1, ticks);
	}
}
