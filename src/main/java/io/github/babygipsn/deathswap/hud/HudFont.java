package io.github.babygipsn.deathswap.hud;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.babygipsn.deathswap.Deathswap;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Encodes HUD lines for vanilla clients that loaded the Deathswap resource pack.
 * <p>
 * The pack defines fonts {@code deathswap:hud/y<N>} that draw the vanilla ASCII glyphs N pixels lower,
 * plus private-use characters with negative/positive advances. Each line is drawn in the font for its
 * row, then "rewound" by its own width so all lines start at the same x. The whole component nets to
 * zero width, so a centered element (the boss bar title) places the block at center - offsetX.
 */
public final class HudFont {
	private static final Map<Character, Integer> ADVANCES = new HashMap<>();
	private static int shiftMax;
	private static int shiftStep;
	private static int negBase;
	private static int posBase;
	private static int powCount;
	public static int lineHeight = 9;
	public static String packSha1 = "";
	public static String packDistFile = "";

	private HudFont() {
	}

	public static void load() {
		try (InputStream in = HudFont.class.getResourceAsStream("/deathswap/hud_glyphs.json")) {
			JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			shiftMax = root.get("shiftMax").getAsInt();
			shiftStep = root.get("shiftStep").getAsInt();
			negBase = root.get("negBase").getAsInt();
			posBase = root.get("posBase").getAsInt();
			powCount = root.get("powCount").getAsInt();
			lineHeight = root.get("lineHeight").getAsInt();
			packSha1 = root.get("sha1").getAsString();
			packDistFile = root.get("distFile").getAsString();
			root.getAsJsonObject("advances").entrySet().forEach(e -> ADVANCES.put(e.getKey().charAt(0), e.getValue().getAsInt()));
		} catch (Exception e) {
			Deathswap.LOGGER.error("Could not load HUD glyph table", e);
		}
	}

	/** Characters without a glyph in the pack fonts are transliterated or replaced. */
	public static String sanitize(String s) {
		StringBuilder sb = new StringBuilder(s.length());
		for (char c : s.toCharArray()) {
			switch (c) {
				case 'ä' -> sb.append("ae");
				case 'ö' -> sb.append("oe");
				case 'ü' -> sb.append("ue");
				case 'Ä' -> sb.append("Ae");
				case 'Ö' -> sb.append("Oe");
				case 'Ü' -> sb.append("Ue");
				case 'ß' -> sb.append("ss");
				default -> sb.append(ADVANCES.containsKey(c) ? c : '?');
			}
		}
		return sb.toString();
	}

	public static int width(String s, boolean bold) {
		int w = 0;
		for (char c : s.toCharArray()) {
			w += ADVANCES.getOrDefault(c, 6) + (bold ? 1 : 0);
		}
		return w;
	}

	/** Builds the zero-width component; offsetX = px left of center, offsetY = px below the anchor. */
	public static Component encode(List<HudLine> lines, int offsetX, int offsetY) {
		MutableComponent root = Component.empty().withStyle(Style.EMPTY.withFont(font("space")).withColor(0xFFFFFF));
		root.append(shift(-offsetX));
		int y = offsetY;
		for (HudLine line : lines) {
			int row = Math.min(shiftMax, Math.max(0, Math.round(y / (float) shiftStep) * shiftStep));
			FontDescription rowFont = font("y" + row);
			int pen = 0;
			for (HudLine.Span span : line.spans()) {
				if (span.tab() > pen) {
					root.append(shift(span.tab() - pen));
					pen = span.tab();
				}
				String text = sanitize(span.text());
				if (text.isEmpty()) continue;
				pen += width(text, span.bold());
				root.append(Component.literal(text).withStyle(Style.EMPTY.withFont(rowFont)
						.withColor(TextColor.fromRgb(span.color())).withBold(span.bold())));
			}
			if (pen != 0) root.append(shift(-pen));
			y += lineHeight;
		}
		root.append(shift(offsetX));
		return root;
	}

	private static FontDescription font(String name) {
		return new FontDescription.Resource(Deathswap.id("hud/" + name));
	}

	/** Text that moves the cursor by {@code px} (negative = left) without drawing anything. */
	private static Component shift(int px) {
		StringBuilder sb = new StringBuilder();
		int base = px < 0 ? negBase : posBase;
		int remaining = Math.abs(px);
		for (int k = powCount - 1; k >= 0 && remaining > 0; k--) {
			int v = 1 << k;
			while (remaining >= v) {
				sb.append((char) (base + k));
				remaining -= v;
			}
		}
		return Component.literal(sb.toString()).withStyle(Style.EMPTY.withFont(font("space")));
	}
}
