package io.github.babygipsn.deathswap.config;

import java.util.ArrayList;
import java.util.List;

/**
 * All server-side settings. Serialized to config/deathswap.json with Gson.
 * Every field annotated with {@link Setting} is editable in-game; the key is "category.field".
 */
public class DeathswapConfig {
	public General general = new General();
	public Game game = new Game();
	public World world = new World();
	public Lobby lobby = new Lobby();
	public Hud hud = new Hud();
	public RandomItems randomItems = new RandomItems();
	public StructurePlacers structurePlacers = new StructurePlacers();
	public Events events = new Events();
	public StartKit startKit = new StartKit();
	public ResourcePack resourcePack = new ResourcePack();

	public enum Language { AUTO, EN_US, DE_DE }

	public enum TimerMode { VISIBLE, HIDDEN }

	public enum RespawnMode { BED_OR_START, START, RANDOM }

	public enum LateJoin { SPECTATE, NEXT_ROUND }

	public enum Tiebreaker { TRAP_KILLS, NONE }

	public enum SeedMode { RANDOM, FIXED, MAIN_WORLD }

	public enum LobbyMode { ADVENTURE, SURVIVAL, CREATIVE, SPECTATOR }

	public enum PackMode { GITHUB, BUILTIN_HOST, CUSTOM_URL, OFF }

	public static class General {
		@Setting(icon = "minecraft:writable_book")
		public Language language = Language.AUTO;
	}

	public static class Game {
		@Setting(min = 1, max = 100, icon = "minecraft:repeater")
		public int rounds = 3;
		@Setting(min = 30, max = 36000, step = 60, icon = "minecraft:clock")
		public int roundSeconds = 1200;
		@Setting(min = 5, max = 3600, step = 30, icon = "minecraft:ender_pearl")
		public int swapIntervalSeconds = 300;
		@Setting(icon = "minecraft:spyglass")
		public TimerMode timerMode = TimerMode.VISIBLE;
		@Setting(min = 0, max = 120, icon = "minecraft:bell")
		public int warningSeconds = 10;
		@Setting(min = 0, max = 60, icon = "minecraft:totem_of_undying")
		public int graceSeconds = 0;
		@Setting(min = 0, max = 300, icon = "minecraft:tripwire_hook")
		public int trapCreditSeconds = 20;
		@Setting(icon = "minecraft:golden_sword")
		public Tiebreaker tiebreaker = Tiebreaker.TRAP_KILLS;
		@Setting(min = 3, max = 600, step = 5, icon = "minecraft:hopper")
		public int intermissionSeconds = 15;
		@Setting(icon = "minecraft:chest")
		public boolean keepInventory = false;
		@Setting(icon = "minecraft:red_bed")
		public RespawnMode respawnMode = RespawnMode.BED_OR_START;
		@Setting(icon = "minecraft:oak_door")
		public LateJoin lateJoin = LateJoin.NEXT_ROUND;
		@Setting(icon = "minecraft:firework_rocket")
		public boolean swapEffects = true;
		@Setting(min = 0, max = 10, icon = "minecraft:note_block")
		public int countdownTitleSeconds = 5;
	}

	public static class World {
		@Setting(icon = "minecraft:wheat_seeds")
		public SeedMode seedMode = SeedMode.RANDOM;
		@Setting(icon = "minecraft:name_tag")
		public String fixedSeed = "deathswap";
		/** Any world preset id, e.g. minecraft:normal, minecraft:large_biomes, minecraft:amplified. */
		@Setting(icon = "minecraft:grass_block")
		public String worldPreset = "minecraft:normal";
		@Setting(icon = "minecraft:netherrack")
		public boolean netherEnabled = true;
		@Setting(icon = "minecraft:end_stone")
		public boolean endEnabled = true;
		@Setting(min = 50, max = 100000, step = 100, icon = "minecraft:map")
		public int spreadRadius = 1000;
		@Setting(min = 0, max = 10000, step = 50, icon = "minecraft:compass")
		public int minPlayerDistance = 250;
		/** 0 disables the border. */
		@Setting(min = 0, max = 1000000, step = 500, icon = "minecraft:barrier")
		public int worldBorderSize = 0;
	}

	public static class Lobby {
		@Setting(icon = "minecraft:lever")
		public boolean autoStart = true;
		@Setting(min = 1, max = 1000, icon = "minecraft:player_head")
		public int minPlayers = 2;
		@Setting(min = 0, max = 120, icon = "minecraft:clock")
		public int startCountdownSeconds = 10;
		@Setting(icon = "minecraft:shield")
		public boolean protectLobby = true;
		@Setting(icon = "minecraft:grass_block")
		public LobbyMode lobbyGameMode = LobbyMode.ADVENTURE;
	}

	public static class Hud {
		@Setting(icon = "minecraft:item_frame")
		public boolean enabled = true;
		/** Resource-pack HUD: default distance of the left edge from the screen center (GUI px). */
		@Setting(min = 0, max = 1000, step = 10, icon = "minecraft:arrow")
		public int defaultOffsetX = 200;
		/** Resource-pack HUD: default distance from the top of the screen (GUI px). */
		@Setting(min = 0, max = 300, step = 10, icon = "minecraft:arrow")
		public int defaultOffsetY = 40;
		@Setting(min = 1, max = 30, icon = "minecraft:player_head")
		public int maxPlayersShown = 8;
	}

	public static class RandomItems {
		@Setting(icon = "minecraft:chest")
		public boolean enabled = true;
		@Setting(min = 1, max = 3600, step = 5, icon = "minecraft:clock")
		public int intervalSeconds = 10;
		@Setting(min = 1, max = 64, icon = "minecraft:bundle")
		public int amount = 1;
		@Setting(icon = "minecraft:diamond")
		public boolean randomStackSize = false;
		/** Chance (0-1) that a random item is a structure placer instead. */
		@Setting(min = 0, max = 1, step = 0.01, icon = "minecraft:knowledge_book")
		public double placerChance = 0.03;
		/** Items never given. Accepts item ids and #tags. */
		public List<String> blacklist = new ArrayList<>(List.of(
				"minecraft:air", "minecraft:command_block", "minecraft:chain_command_block", "minecraft:repeating_command_block",
				"minecraft:command_block_minecart", "minecraft:structure_block", "minecraft:structure_void", "minecraft:jigsaw",
				"minecraft:barrier", "minecraft:light", "minecraft:debug_stick", "minecraft:knowledge_book", "minecraft:bedrock",
				"minecraft:end_portal_frame", "minecraft:spawner", "minecraft:trial_spawner", "minecraft:vault",
				"minecraft:test_block", "minecraft:test_instance_block", "minecraft:reinforced_deepslate",
				"minecraft:petrified_oak_slab", "minecraft:player_head", "minecraft:written_book", "minecraft:filled_map",
				"minecraft:budding_amethyst", "minecraft:farmland", "minecraft:dirt_path", "minecraft:frogspawn"));
		@Setting(icon = "minecraft:zombie_spawn_egg")
		public boolean allowSpawnEggs = false;
	}

	public static class StructurePlacers {
		@Setting(icon = "minecraft:knowledge_book")
		public boolean enabled = true;
		/** Structure ids that are never handed out as placers. */
		public List<String> blacklist = new ArrayList<>();
	}

	public static class Events {
		@Setting(icon = "minecraft:tnt")
		public boolean enabled = true;
		@Setting(min = 10, max = 3600, step = 10, icon = "minecraft:clock")
		public int intervalSeconds = 120;
		@Setting(icon = "minecraft:tnt")
		public boolean tntRain = true;
		@Setting(icon = "minecraft:anvil")
		public boolean anvilRain = true;
		@Setting(icon = "minecraft:lightning_rod")
		public boolean lightning = true;
		@Setting(icon = "minecraft:potion")
		public boolean randomEffect = true;
		@Setting(icon = "minecraft:shulker_shell")
		public boolean levitation = true;
		@Setting(icon = "minecraft:zombie_head")
		public boolean mobSwarm = true;
		@Setting(icon = "minecraft:bundle")
		public boolean inventoryShuffle = true;
		@Setting(icon = "minecraft:sugar")
		public boolean speedBoost = true;
	}

	public static class StartKit {
		@Setting(icon = "minecraft:chest")
		public boolean enabled = true;
		/** Entries like "minecraft:bread 8". */
		public List<String> items = new ArrayList<>(List.of("minecraft:bread 8"));
	}

	public static class ResourcePack {
		@Setting(icon = "minecraft:painting")
		public PackMode mode = PackMode.GITHUB;
		/** Base URL for GITHUB mode; the pack file name is appended. */
		public String githubBaseUrl = "https://raw.githubusercontent.com/babygipsn/Deathswap/main/resourcepack/dist/";
		/** Full URL for CUSTOM_URL mode (must serve exactly the bundled zip so the SHA-1 matches). */
		public String customUrl = "";
		/** Public host/IP players use to reach this server, for BUILTIN_HOST mode. */
		public String hostAddress = "";
		@Setting(min = 1, max = 65535, icon = "minecraft:tripwire_hook")
		public int hostPort = 25580;
		@Setting(icon = "minecraft:iron_bars")
		public boolean required = false;
	}
}
