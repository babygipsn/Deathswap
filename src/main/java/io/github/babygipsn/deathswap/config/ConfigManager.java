package io.github.babygipsn.deathswap.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.babygipsn.deathswap.Deathswap;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Loads/saves {@link DeathswapConfig} and exposes every field as a "category.field" entry. */
public final class ConfigManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("deathswap.json");

	private static DeathswapConfig config = new DeathswapConfig();
	private static List<Entry> entries;

	private ConfigManager() {
	}

	public static DeathswapConfig get() {
		return config;
	}

	public static void load() {
		if (Files.exists(FILE)) {
			try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
				DeathswapConfig loaded = GSON.fromJson(reader, DeathswapConfig.class);
				config = loaded != null ? fillNulls(loaded) : new DeathswapConfig();
			} catch (Exception e) {
				Deathswap.LOGGER.error("Could not read {}, using defaults", FILE, e);
				config = new DeathswapConfig();
			}
		} else {
			config = new DeathswapConfig();
		}
		entries = null;
		save(); // writes new keys added by updates
	}

	public static void save() {
		try {
			Files.createDirectories(FILE.getParent());
			try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
				GSON.toJson(config, writer);
			}
		} catch (IOException e) {
			Deathswap.LOGGER.error("Could not write {}", FILE, e);
		}
	}

	/** Gson leaves missing categories null and missing fields at their Java defaults; repair both. */
	private static DeathswapConfig fillNulls(DeathswapConfig loaded) {
		DeathswapConfig defaults = new DeathswapConfig();
		try {
			for (Field cat : DeathswapConfig.class.getFields()) {
				if (cat.get(loaded) == null) {
					cat.set(loaded, cat.get(defaults));
					continue;
				}
				Object loadedCat = cat.get(loaded);
				Object defaultCat = cat.get(defaults);
				for (Field f : cat.getType().getFields()) {
					if (!Modifier.isStatic(f.getModifiers()) && !f.getType().isPrimitive() && f.get(loadedCat) == null) {
						f.set(loadedCat, f.get(defaultCat));
					}
				}
			}
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
		return loaded;
	}

	public static List<Entry> entries() {
		if (entries == null) {
			List<Entry> list = new ArrayList<>();
			for (Field cat : DeathswapConfig.class.getFields()) {
				for (Field f : cat.getType().getFields()) {
					if (Modifier.isStatic(f.getModifiers())) {
						continue;
					}
					list.add(new Entry(cat.getName() + "." + f.getName(), cat, f, f.getAnnotation(Setting.class)));
				}
			}
			entries = List.copyOf(list);
		}
		return entries;
	}

	public static List<String> categories() {
		return Arrays.stream(DeathswapConfig.class.getFields()).map(Field::getName).toList();
	}

	public static Optional<Entry> entry(String key) {
		return entries().stream().filter(e -> e.key.equalsIgnoreCase(key)).findFirst();
	}

	/** Snapshot for the client config screen: { key: {type, value, min, max, step, options} }. */
	public static JsonObject toSchemaJson() {
		JsonObject root = new JsonObject();
		for (Entry e : entries()) {
			JsonObject o = new JsonObject();
			o.addProperty("type", e.kind().name());
			o.addProperty("value", e.getAsString());
			if (e.setting != null) {
				if (e.setting.min() != -Double.MAX_VALUE) o.addProperty("min", e.setting.min());
				if (e.setting.max() != Double.MAX_VALUE) o.addProperty("max", e.setting.max());
				o.addProperty("step", e.setting.step());
			}
			if (e.kind() == Kind.ENUM) {
				JsonArray options = new JsonArray();
				for (Object c : e.field.getType().getEnumConstants()) options.add(((Enum<?>) c).name());
				o.add("options", options);
			}
			root.add(e.key, o);
		}
		return root;
	}

	public enum Kind { BOOLEAN, INT, LONG, DOUBLE, STRING, ENUM, LIST }

	public static final class Entry {
		public final String key;
		public final Field category;
		public final Field field;
		public final Setting setting;

		Entry(String key, Field category, Field field, Setting setting) {
			this.key = key;
			this.category = category;
			this.field = field;
			this.setting = setting;
		}

		public String categoryName() {
			return this.category.getName();
		}

		public String name() {
			return this.field.getName();
		}

		public Kind kind() {
			Class<?> t = this.field.getType();
			if (t == boolean.class) return Kind.BOOLEAN;
			if (t == int.class) return Kind.INT;
			if (t == long.class) return Kind.LONG;
			if (t == double.class) return Kind.DOUBLE;
			if (t.isEnum()) return Kind.ENUM;
			if (List.class.isAssignableFrom(t)) return Kind.LIST;
			return Kind.STRING;
		}

		public Object getRaw() {
			try {
				return this.field.get(this.category.get(config));
			} catch (IllegalAccessException e) {
				throw new IllegalStateException(e);
			}
		}

		public String getAsString() {
			Object v = this.getRaw();
			if (v instanceof List<?> list) {
				return String.join(", ", list.stream().map(String::valueOf).toList());
			}
			if (v instanceof Double d) {
				return d == Math.rint(d) ? String.valueOf(d.longValue()) : String.valueOf(d);
			}
			return String.valueOf(v);
		}

		/** Parses and stores the value (clamped to bounds). Returns an error message or null. */
		public String set(String input) {
			try {
				Object target = this.category.get(config);
				String s = input.trim();
				switch (this.kind()) {
					case BOOLEAN -> {
						String l = s.toLowerCase(Locale.ROOT);
						if (!l.equals("true") && !l.equals("false") && !l.equals("on") && !l.equals("off")) {
							return "expected true/false";
						}
						this.field.setBoolean(target, l.equals("true") || l.equals("on"));
					}
					case INT -> this.field.setInt(target, (int) this.clamp(Long.parseLong(s)));
					case LONG -> this.field.setLong(target, (long) this.clamp(Long.parseLong(s)));
					case DOUBLE -> this.field.setDouble(target, this.clamp(Double.parseDouble(s)));
					case ENUM -> {
						Object match = null;
						for (Object c : this.field.getType().getEnumConstants()) {
							if (((Enum<?>) c).name().equalsIgnoreCase(s)) match = c;
						}
						if (match == null) {
							return "expected one of " + Arrays.toString(this.field.getType().getEnumConstants());
						}
						this.field.set(target, match);
					}
					case LIST -> {
						List<String> list = new ArrayList<>();
						for (String part : s.split(",")) {
							if (!part.isBlank()) list.add(part.trim());
						}
						this.field.set(target, list);
					}
					case STRING -> this.field.set(target, s);
				}
				return null;
			} catch (NumberFormatException e) {
				return "not a number: " + input;
			} catch (IllegalAccessException e) {
				throw new IllegalStateException(e);
			}
		}

		/** Menu helper: adds {@code steps} increments (numbers), toggles booleans, cycles enums. */
		public void nudge(int steps) {
			Object v = this.getRaw();
			switch (this.kind()) {
				case BOOLEAN -> this.set(String.valueOf(!(Boolean) v));
				case INT, LONG -> this.set(String.valueOf(((Number) v).longValue() + (long) (steps * this.step())));
				case DOUBLE -> this.set(String.valueOf(Math.round((((Number) v).doubleValue() + steps * this.step()) * 1000.0) / 1000.0));
				case ENUM -> {
					Object[] values = this.field.getType().getEnumConstants();
					int i = ((Enum<?>) v).ordinal();
					int n = Math.floorMod(i + (steps >= 0 ? 1 : -1), values.length);
					this.set(((Enum<?>) values[n]).name());
				}
				default -> {
				}
			}
		}

		public double step() {
			return this.setting != null ? this.setting.step() : 1;
		}

		public String icon() {
			if (this.setting != null) return this.setting.icon();
			return this.kind() == Kind.LIST ? "minecraft:writable_book" : "minecraft:name_tag";
		}

		private double clamp(double v) {
			if (this.setting == null) return v;
			return Math.max(this.setting.min(), Math.min(this.setting.max(), v));
		}
	}

	/** Simple per-category grouping, preserving declaration order. */
	public static Map<String, List<Entry>> byCategory() {
		Map<String, List<Entry>> map = new LinkedHashMap<>();
		for (Entry e : entries()) {
			map.computeIfAbsent(e.categoryName(), k -> new ArrayList<>()).add(e);
		}
		return map;
	}
}
