package io.github.babygipsn.deathswap.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.babygipsn.deathswap.Deathswap;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Client-only HUD preferences, stored in config/deathswap-client.json. */
public final class ClientConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("deathswap-client.json");
	private static ClientConfig instance = new ClientConfig();

	public int hudX = 4;
	/** -1 = vertically centered. */
	public int hudY = -1;
	public float scale = 1f;
	public boolean background = true;

	public static ClientConfig get() {
		return instance;
	}

	public static void load() {
		if (!Files.exists(FILE)) {
			save();
			return;
		}
		try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
			ClientConfig loaded = GSON.fromJson(reader, ClientConfig.class);
			if (loaded != null) instance = loaded;
		} catch (Exception e) {
			Deathswap.LOGGER.error("Could not read {}", FILE, e);
		}
	}

	public static void save() {
		try {
			Files.createDirectories(FILE.getParent());
			try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
				GSON.toJson(instance, writer);
			}
		} catch (Exception e) {
			Deathswap.LOGGER.error("Could not write {}", FILE, e);
		}
	}
}
