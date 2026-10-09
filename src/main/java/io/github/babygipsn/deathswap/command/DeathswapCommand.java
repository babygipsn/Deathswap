package io.github.babygipsn.deathswap.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.github.babygipsn.deathswap.Deathswap;
import io.github.babygipsn.deathswap.config.ConfigManager;
import io.github.babygipsn.deathswap.config.PlayerPrefs;
import io.github.babygipsn.deathswap.feature.RandomEvents;
import io.github.babygipsn.deathswap.feature.RandomItems;
import io.github.babygipsn.deathswap.feature.StartKit;
import io.github.babygipsn.deathswap.feature.StructurePlacers;
import io.github.babygipsn.deathswap.game.Fx;
import io.github.babygipsn.deathswap.game.GameManager;
import io.github.babygipsn.deathswap.game.PlayerState;
import io.github.babygipsn.deathswap.hud.HudManager;
import io.github.babygipsn.deathswap.lang.Lang;
import io.github.babygipsn.deathswap.menu.ConfigMenu;
import io.github.babygipsn.deathswap.net.DeathswapNetworking.HudPayload;
import io.github.babygipsn.deathswap.net.DeathswapNetworking.OpenConfigPayload;
import io.github.babygipsn.deathswap.util.Perms;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;

import java.util.Arrays;
import java.util.Locale;

/** /deathswap (alias /ds) and /ready. */
public final class DeathswapCommand {
	private static final SuggestionProvider<CommandSourceStack> CONFIG_KEYS = (ctx, builder) ->
			SharedSuggestionProvider.suggest(ConfigManager.entries().stream().map(e -> e.key), builder);

	private static final SuggestionProvider<CommandSourceStack> CONFIG_VALUES = (ctx, builder) -> {
		String key = StringArgumentType.getString(ctx, "key");
		return ConfigManager.entry(key).map(e -> switch (e.kind()) {
			case BOOLEAN -> SharedSuggestionProvider.suggest(new String[]{"true", "false"}, builder);
			case ENUM -> SharedSuggestionProvider.suggest(Arrays.stream(e.field.getType().getEnumConstants()).map(c -> ((Enum<?>) c).name()), builder);
			default -> SharedSuggestionProvider.suggest(new String[]{e.getAsString()}, builder);
		}).orElseGet(builder::buildFuture);
	};

	private static final SuggestionProvider<CommandSourceStack> STRUCTURES = (ctx, builder) ->
			SharedSuggestionProvider.suggestResource(ctx.getSource().registryAccess().lookupOrThrow(Registries.STRUCTURE).listElementIds().map(ResourceKey::identifier), builder);

	private static final SuggestionProvider<CommandSourceStack> EVENTS = (ctx, builder) ->
			SharedSuggestionProvider.suggest(Arrays.stream(RandomEvents.Event.values()).map(e -> e.name().toLowerCase(Locale.ROOT)), builder);

	private DeathswapCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("deathswap")
				.executes(DeathswapCommand::status)
				.then(Commands.literal("help").executes(DeathswapCommand::help))
				.then(Commands.literal("status").executes(DeathswapCommand::status))
				.then(Commands.literal("stats").executes(DeathswapCommand::stats))
				.then(Commands.literal("ready").executes(ctx -> {
					game().toggleReady(ctx.getSource().getPlayerOrException());
					return 1;
				}))
				.then(Commands.literal("spectate").executes(ctx -> {
					game().toggleOptOut(ctx.getSource().getPlayerOrException());
					return 1;
				}))
				.then(hud())
				.then(Commands.literal("start").requires(Perms::isAdmin).executes(ctx -> start(ctx, true))
						.then(Commands.literal("vote").executes(ctx -> start(ctx, false))))
				.then(Commands.literal("stop").requires(Perms::isAdmin).executes(ctx -> {
					game().stop(true);
					return 1;
				}))
				.then(Commands.literal("swap").requires(Perms::isAdmin).executes(ctx -> {
					if (!running(ctx)) return 0;
					game().swap();
					return 1;
				}))
				.then(Commands.literal("endround").requires(Perms::isAdmin).executes(ctx -> {
					if (!running(ctx)) return 0;
					game().setRoundTicksLeft(1);
					return 1;
				}))
				.then(Commands.literal("time").requires(Perms::isAdmin)
						.then(Commands.literal("swap").then(Commands.argument("seconds", IntegerArgumentType.integer(1)).executes(ctx -> {
							if (!running(ctx)) return 0;
							game().setSwapTicksLeft(IntegerArgumentType.getInteger(ctx, "seconds") * 20);
							return 1;
						})))
						.then(Commands.literal("round").then(Commands.argument("seconds", IntegerArgumentType.integer(1)).executes(ctx -> {
							if (!running(ctx)) return 0;
							game().setRoundTicksLeft(IntegerArgumentType.getInteger(ctx, "seconds") * 20);
							return 1;
						}))))
				.then(Commands.literal("event").requires(Perms::isAdmin)
						.executes(ctx -> event(ctx, null))
						.then(Commands.argument("name", StringArgumentType.word()).suggests(EVENTS)
								.executes(ctx -> event(ctx, StringArgumentType.getString(ctx, "name")))))
				.then(Commands.literal("item").requires(Perms::isAdmin)
						.executes(ctx -> {
							RandomItems.give(ctx.getSource().getPlayerOrException());
							return 1;
						}))
				.then(Commands.literal("placer").requires(Perms::isAdmin)
						.then(Commands.argument("structure", IdentifierArgument.id()).suggests(STRUCTURES)
								.executes(ctx -> placer(ctx, ctx.getSource().getPlayerOrException()))
								.then(Commands.argument("player", EntityArgument.player())
										.executes(ctx -> placer(ctx, EntityArgument.getPlayer(ctx, "player"))))))
				.then(config());

		LiteralCommandNode<CommandSourceStack> node = dispatcher.register(root);
		dispatcher.register(Commands.literal("ds").executes(DeathswapCommand::status).redirect(node));
		dispatcher.register(Commands.literal("ready").executes(ctx -> {
			game().toggleReady(ctx.getSource().getPlayerOrException());
			return 1;
		}));
	}

	private static GameManager game() {
		return GameManager.get();
	}

	private static void reply(CommandContext<CommandSourceStack> ctx, String key, Object... args) {
		ServerPlayer p = ctx.getSource().getPlayer();
		ctx.getSource().sendSuccess(() -> Fx.prefix().append(Lang.tr(p, key, args).withStyle(ChatFormatting.GRAY)), false);
	}

	private static void fail(CommandContext<CommandSourceStack> ctx, String key, Object... args) {
		ServerPlayer p = ctx.getSource().getPlayer();
		ctx.getSource().sendFailure(Lang.tr(p, key, args));
	}

	private static boolean running(CommandContext<CommandSourceStack> ctx) {
		if (game().state() != GameManager.State.RUNNING) {
			fail(ctx, "deathswap.err.not_running");
			return false;
		}
		return true;
	}

	private static int help(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer p = ctx.getSource().getPlayer();
		String[] keys = Perms.isAdmin(ctx.getSource())
				? new String[]{"ready", "spectate", "status", "stats", "hud", "start", "stop", "swap", "endround", "time", "event", "item", "placer", "config"}
				: new String[]{"ready", "spectate", "status", "stats", "hud"};
		ctx.getSource().sendSuccess(() -> Fx.prefix().append(Lang.tr(p, "deathswap.help.header").withStyle(ChatFormatting.GOLD)), false);
		for (String k : keys) {
			ctx.getSource().sendSuccess(() -> Component.literal(" /ds " + k + " ").withStyle(ChatFormatting.YELLOW)
					.append(Lang.tr(p, "deathswap.help." + k).withStyle(ChatFormatting.GRAY)), false);
		}
		return 1;
	}

	private static int status(CommandContext<CommandSourceStack> ctx) {
		GameManager g = game();
		switch (g.state()) {
			case LOBBY -> reply(ctx, "deathswap.status.lobby", g.readyCount(), g.lobbyCandidates().size());
			case STARTING -> reply(ctx, "deathswap.status.starting", Fx.time(g.countdown()));
			case RUNNING -> reply(ctx, "deathswap.status.running", g.round(), g.totalRounds(), Fx.time(g.roundTicksLeft()), g.players().size());
			case INTERMISSION -> reply(ctx, "deathswap.status.intermission", g.round(), g.totalRounds(), Fx.time(g.countdown()));
		}
		return 1;
	}

	private static int stats(CommandContext<CommandSourceStack> ctx) {
		GameManager g = game();
		if (g.players().isEmpty()) {
			fail(ctx, "deathswap.err.no_stats");
			return 0;
		}
		ServerPlayer p = ctx.getSource().getPlayer();
		int place = 1;
		for (PlayerState ps : g.ranking()) {
			int rank = place++;
			ctx.getSource().sendSuccess(() -> Component.literal(" " + rank + ". ").withStyle(ChatFormatting.YELLOW)
					.append(Lang.tr(p, "deathswap.msg.final_line", ps.name, ps.deaths, ps.trapKills, g.causeSummary(ps)).withStyle(ChatFormatting.GRAY)), false);
		}
		return 1;
	}

	private static int start(CommandContext<CommandSourceStack> ctx, boolean force) {
		String err = game().requestStart(force);
		if (err != null) {
			fail(ctx, err);
			return 0;
		}
		return 1;
	}

	private static int event(CommandContext<CommandSourceStack> ctx, String name) {
		if (!running(ctx)) return 0;
		RandomEvents.Event event = null;
		if (name != null) {
			try {
				event = RandomEvents.Event.valueOf(name.toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				fail(ctx, "deathswap.err.unknown_event", name);
				return 0;
			}
		}
		RandomEvents.trigger(game(), event);
		return 1;
	}

	private static int placer(CommandContext<CommandSourceStack> ctx, ServerPlayer target) {
		Identifier id = IdentifierArgument.getId(ctx, "structure");
		var structure = ctx.getSource().registryAccess().lookupOrThrow(Registries.STRUCTURE).get(ResourceKey.create(Registries.STRUCTURE, id));
		if (structure.isEmpty()) {
			fail(ctx, "deathswap.msg.placer_unknown", id.toString());
			return 0;
		}
		StartKit.giveOrDrop(target, StructurePlacers.create(target, structure.get()));
		return 1;
	}

	// ------------------------------------------------------------------ /ds hud

	private static LiteralArgumentBuilder<CommandSourceStack> hud() {
		return Commands.literal("hud")
				.executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					reply(ctx, "deathswap.msg.hud_info", HudManager.mode(p).name(), PlayerPrefs.hudX(p.getUUID()), PlayerPrefs.hudY(p.getUUID()));
					return 1;
				})
				.then(Commands.literal("x").then(Commands.argument("px", IntegerArgumentType.integer(0, 1000)).executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					PlayerPrefs.get(p.getUUID()).hudX = IntegerArgumentType.getInteger(ctx, "px");
					return hudSaved(ctx, p);
				})))
				.then(Commands.literal("y").then(Commands.argument("px", IntegerArgumentType.integer(0, 300)).executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					PlayerPrefs.get(p.getUUID()).hudY = IntegerArgumentType.getInteger(ctx, "px");
					return hudSaved(ctx, p);
				})))
				.then(Commands.literal("reset").executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					PlayerPrefs.Prefs prefs = PlayerPrefs.get(p.getUUID());
					prefs.hudX = null;
					prefs.hudY = null;
					prefs.hudHidden = false;
					return hudSaved(ctx, p);
				}))
				.then(Commands.literal("hide").executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					PlayerPrefs.get(p.getUUID()).hudHidden = true;
					return hudSaved(ctx, p);
				}))
				.then(Commands.literal("show").executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					PlayerPrefs.get(p.getUUID()).hudHidden = false;
					return hudSaved(ctx, p);
				}));
	}

	private static int hudSaved(CommandContext<CommandSourceStack> ctx, ServerPlayer p) {
		PlayerPrefs.save();
		HudManager.invalidate(p);
		reply(ctx, "deathswap.msg.hud_saved", PlayerPrefs.hudX(p.getUUID()), PlayerPrefs.hudY(p.getUUID()));
		return 1;
	}

	// ------------------------------------------------------------------ /ds config

	private static LiteralArgumentBuilder<CommandSourceStack> config() {
		return Commands.literal("config").requires(Perms::isAdmin)
				.executes(DeathswapCommand::openConfig)
				.then(Commands.literal("menu").executes(ctx -> {
					ConfigMenu.open(ctx.getSource().getPlayerOrException());
					return 1;
				}))
				.then(Commands.literal("reload").executes(ctx -> {
					ConfigManager.load();
					Deathswap.onConfigChanged();
					reply(ctx, "deathswap.msg.config_reloaded");
					return 1;
				}))
				.then(Commands.literal("list").executes(ctx -> {
					for (ConfigManager.Entry e : ConfigManager.entries()) {
						ctx.getSource().sendSuccess(() -> Component.literal(e.key + " = ").withStyle(ChatFormatting.GRAY)
								.append(Component.literal(e.getAsString()).withStyle(ChatFormatting.WHITE)), false);
					}
					return 1;
				}))
				.then(Commands.literal("get").then(Commands.argument("key", StringArgumentType.string()).suggests(CONFIG_KEYS).executes(ctx -> {
					String key = StringArgumentType.getString(ctx, "key");
					var entry = ConfigManager.entry(key);
					if (entry.isEmpty()) {
						fail(ctx, "deathswap.err.unknown_key", key);
						return 0;
					}
					reply(ctx, "deathswap.msg.config_value", entry.get().key, entry.get().getAsString());
					return 1;
				})))
				.then(Commands.literal("set").then(Commands.argument("key", StringArgumentType.string()).suggests(CONFIG_KEYS)
						.then(Commands.argument("value", StringArgumentType.greedyString()).suggests(CONFIG_VALUES).executes(ctx -> {
							String key = StringArgumentType.getString(ctx, "key");
							var entry = ConfigManager.entry(key);
							if (entry.isEmpty()) {
								fail(ctx, "deathswap.err.unknown_key", key);
								return 0;
							}
							String error = entry.get().set(StringArgumentType.getString(ctx, "value"));
							if (error != null) {
								fail(ctx, "deathswap.err.bad_value", error);
								return 0;
							}
							Deathswap.onConfigChanged();
							reply(ctx, "deathswap.msg.config_value", entry.get().key, entry.get().getAsString());
							return 1;
						}))));
	}

	/** Opens the rich screen for clients with the mod, the chest menu otherwise. */
	private static int openConfig(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		if (ServerPlayNetworking.canSend(p, OpenConfigPayload.TYPE) && ServerPlayNetworking.canSend(p, HudPayload.TYPE)) {
			ServerPlayNetworking.send(p, new OpenConfigPayload(ConfigManager.toSchemaJson().toString()));
		} else {
			ConfigMenu.open(p);
		}
		return 1;
	}
}
