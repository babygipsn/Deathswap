package io.github.babygipsn.deathswap.lang;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.babygipsn.deathswap.Deathswap;
import io.github.babygipsn.deathswap.config.ConfigManager;
import io.github.babygipsn.deathswap.config.DeathswapConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Server-side translations, so vanilla clients (without the mod's lang files) see localized text.
 * Uses the same files the client mod ships: assets/deathswap/lang/*.json.
 */
public final class Lang {
	public static final String EN = "en_us";
	public static final String DE = "de_de";
	private static final Map<String, Map<String, String>> TABLES = new HashMap<>();

	private Lang() {
	}

	public static void load() {
		for (String code : new String[]{EN, DE}) {
			Map<String, String> table = new HashMap<>();
			try (InputStream in = Lang.class.getResourceAsStream("/assets/deathswap/lang/" + code + ".json")) {
				JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
				root.entrySet().forEach(e -> table.put(e.getKey(), e.getValue().getAsString()));
			} catch (Exception e) {
				Deathswap.LOGGER.error("Could not load language {}", code, e);
			}
			TABLES.put(code, table);
		}
	}

	public static String code(@Nullable ServerPlayer player) {
		DeathswapConfig.Language setting = ConfigManager.get().general.language;
		if (setting == DeathswapConfig.Language.DE_DE) return DE;
		if (setting == DeathswapConfig.Language.EN_US || player == null) return EN;
		String client = player.clientInformation().language().toLowerCase(Locale.ROOT);
		return client.startsWith("de") ? DE : EN;
	}

	public static String raw(@Nullable ServerPlayer player, String key) {
		String value = TABLES.getOrDefault(code(player), Map.of()).get(key);
		if (value == null) value = TABLES.getOrDefault(EN, Map.of()).getOrDefault(key, key);
		return value;
	}

	/** Plain string with %s placeholders filled in (arguments converted with toString / getString). */
	public static String str(@Nullable ServerPlayer player, String key, Object... args) {
		String pattern = raw(player, key);
		StringBuilder sb = new StringBuilder();
		int argIndex = 0;
		int i = 0;
		while (i < pattern.length()) {
			int next = pattern.indexOf("%s", i);
			if (next < 0) {
				sb.append(pattern, i, pattern.length());
				break;
			}
			sb.append(pattern, i, next);
			Object arg = argIndex < args.length ? args[argIndex++] : "";
			sb.append(arg instanceof Component c ? c.getString() : String.valueOf(arg));
			i = next + 2;
		}
		return sb.toString();
	}

	/** Component with %s placeholders; Component arguments keep their own styling. */
	public static MutableComponent tr(@Nullable ServerPlayer player, String key, Object... args) {
		String pattern = raw(player, key);
		MutableComponent out = Component.empty();
		int argIndex = 0;
		int i = 0;
		while (i < pattern.length()) {
			int next = pattern.indexOf("%s", i);
			if (next < 0) {
				out.append(pattern.substring(i));
				break;
			}
			if (next > i) out.append(pattern.substring(i, next));
			Object arg = argIndex < args.length ? args[argIndex++] : "";
			out.append(arg instanceof Component c ? c.copy() : Component.literal(String.valueOf(arg)));
			i = next + 2;
		}
		return out;
	}
}
