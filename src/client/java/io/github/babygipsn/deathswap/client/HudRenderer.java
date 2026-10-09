package io.github.babygipsn.deathswap.client;

import io.github.babygipsn.deathswap.hud.HudLine;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import java.util.List;

/** Draws the server-provided HUD lines at the left edge of the screen. */
public final class HudRenderer {
	private static final int LINE_HEIGHT = 10;
	private static List<HudLine> lines = List.of();

	private HudRenderer() {
	}

	public static void setLines(List<HudLine> newLines) {
		lines = newLines;
	}

	public static void clear() {
		lines = List.of();
	}

	public static void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		List<HudLine> current = lines;
		if (current.isEmpty()) return;
		Minecraft mc = Minecraft.getInstance();
		Font font = mc.font;
		ClientConfig cfg = ClientConfig.get();

		int width = 0;
		for (HudLine line : current) width = Math.max(width, lineWidth(font, line));
		int height = current.size() * LINE_HEIGHT;

		float scale = Math.max(0.25f, Math.min(4f, cfg.scale));
		int screenH = (int) (graphics.guiHeight() / scale);
		int x = cfg.hudX;
		int y = cfg.hudY < 0 ? (screenH - height) / 2 : cfg.hudY;

		graphics.pose().pushMatrix();
		graphics.pose().scale(scale, scale);
		if (cfg.background) {
			graphics.fill(x - 3, y - 3, x + width + 3, y + height + 1, 0x90000000);
		}
		for (HudLine line : current) {
			int pen = 0;
			for (HudLine.Span span : line.spans()) {
				if (span.tab() >= 0) pen = Math.max(pen, span.tab());
				if (span.text().isEmpty()) continue;
				Component text = component(span);
				graphics.text(font, text, x + pen, y, 0xFF000000 | span.color(), true);
				pen += font.width(text);
			}
			y += LINE_HEIGHT;
		}
		graphics.pose().popMatrix();
	}

	private static Component component(HudLine.Span span) {
		return Component.literal(span.text()).withStyle(Style.EMPTY.withBold(span.bold()));
	}

	private static int lineWidth(Font font, HudLine line) {
		int pen = 0;
		for (HudLine.Span span : line.spans()) {
			if (span.tab() >= 0) pen = Math.max(pen, span.tab());
			pen += font.width(component(span));
		}
		return pen;
	}
}
