package io.github.babygipsn.deathswap.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import io.github.babygipsn.deathswap.Deathswap;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Per-player preferences (HUD position for the resource-pack HUD), stored in config/deathswap-players.json. */
public final class PlayerPrefs {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("deathswap-players.json");
	private static Map<UUID, Prefs> prefs = new HashMap<>();

	public static class Prefs {
		/** null = use the server default from the config. */
		public Integer hudX;
		public Integer hudY;
		public boolean hudHidden;
	}

	private PlayerPrefs() {
	}

	public static Prefs get(UUID id) {
		return prefs.computeIfAbsent(id, k -> new Prefs());
	}

	public static int hudX(UUID id) {
		Prefs p = prefs.get(id);
		return p != null && p.hudX != null ? p.hudX : ConfigManager.get().hud.defaultOffsetX;
	}

	public static int hudY(UUID id) {
		Prefs p = prefs.get(id);
		return p != null && p.hudY != null ? p.hudY : ConfigManager.get().hud.defaultOffsetY;
	}

	public static void load() {
		if (!Files.exists(FILE)) return;
		try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
			Map<UUID, Prefs> loaded = GSON.fromJson(reader, new TypeToken<Map<UUID, Prefs>>() { }.getType());
			if (loaded != null) prefs = loaded;
		} catch (Exception e) {
			Deathswap.LOGGER.error("Could not read {}", FILE, e);
		}
	}

	public static void save() {
		try {
			Files.createDirectories(FILE.getParent());
			try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
				GSON.toJson(prefs, writer);
			}
		} catch (Exception e) {
			Deathswap.LOGGER.error("Could not write {}", FILE, e);
		}
	}
}
