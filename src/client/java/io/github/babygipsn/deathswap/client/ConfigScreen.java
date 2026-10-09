package io.github.babygipsn.deathswap.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.babygipsn.deathswap.net.DeathswapNetworking.ConfigUpdatePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.CommonComponents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Config editor for clients with the mod. Built from the schema the server sends, so it always matches
 * the server's settings. Only changed values are sent back; the server re-checks operator permission.
 */
public final class ConfigScreen extends Screen {
	private static final int ROW_HEIGHT = 24;
	private static final int ERROR_COLOR = 0xFFFF5555;
	private static final int TEXT_COLOR = 0xFFE0E0E0;

	private final JsonObject schema;
	private final Map<String, String> changes = new LinkedHashMap<>();
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 33, 33);
	private EntryList list;

	public ConfigScreen(String schemaJson) {
		super(Component.translatable("deathswap.screen.title"));
		this.schema = JsonParser.parseString(schemaJson).getAsJsonObject();
	}

	@Override
	protected void init() {
		this.layout.addTitleHeader(this.title, this.font);
		this.list = this.layout.addToContents(new EntryList());
		LinearLayout footer = this.layout.addToFooter(LinearLayout.horizontal().spacing(8));
		footer.addChild(Button.builder(Component.translatable("deathswap.screen.save"), b -> this.save()).build());
		footer.addChild(Button.builder(CommonComponents.GUI_CANCEL, b -> this.onClose()).build());
		this.layout.visitWidgets(this::addRenderableWidget);
		this.repositionElements();
	}

	@Override
	protected void repositionElements() {
		this.layout.arrangeElements();
		if (this.list != null) this.list.updateSize(this.width, this.layout);
	}

	private void save() {
		if (!this.changes.isEmpty()) {
			JsonObject json = new JsonObject();
			this.changes.forEach(json::addProperty);
			ClientPlayNetworking.send(new ConfigUpdatePayload(json.toString()));
		}
		this.onClose();
	}

	private static Component label(String key) {
		String tkey = "deathswap.config." + key;
		return Language.getInstance().has(tkey) ? Component.translatable(tkey) : Component.literal(key.substring(key.indexOf('.') + 1));
	}

	private static Component categoryLabel(String category) {
		String tkey = "deathswap.menu.category." + category;
		return (Language.getInstance().has(tkey) ? Component.translatable(tkey) : Component.literal(category))
				.copy().withStyle(ChatFormatting.BOLD, ChatFormatting.YELLOW);
	}

	// ------------------------------------------------------------------ list

	private final class EntryList extends ContainerObjectSelectionList<Row> {
		EntryList() {
			super(Minecraft.getInstance(), ConfigScreen.this.width, ConfigScreen.this.layout.getContentHeight(),
					ConfigScreen.this.layout.getHeaderHeight(), ROW_HEIGHT);
			String lastCategory = null;
			for (Map.Entry<String, JsonElement> e : ConfigScreen.this.schema.entrySet()) {
				String key = e.getKey();
				String category = key.substring(0, key.indexOf('.'));
				if (!category.equals(lastCategory)) {
					this.addEntry(new HeaderRow(categoryLabel(category)));
					lastCategory = category;
				}
				this.addEntry(new SettingRow(key, e.getValue().getAsJsonObject()));
			}
		}

		@Override
		public int getRowWidth() {
			return 340;
		}
	}

	private abstract static class Row extends ContainerObjectSelectionList.Entry<Row> {
	}

	private final class HeaderRow extends Row {
		private final StringWidget widget;

		HeaderRow(Component text) {
			this.widget = new StringWidget(text, ConfigScreen.this.font);
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
			this.widget.setPosition(this.getContentXMiddle() - this.widget.getWidth() / 2, this.getContentYMiddle() - this.widget.getHeight() / 2);
			this.widget.extractRenderState(graphics, mouseX, mouseY, a);
		}

		@Override
		public List<? extends GuiEventListener> children() {
			return List.of();
		}

		@Override
		public List<? extends NarratableEntry> narratables() {
			return List.of(this.widget);
		}
	}

	private final class SettingRow extends Row {
		private final Component label;
		private final AbstractWidget widget;
		private final int widgetWidth;

		SettingRow(String key, JsonObject meta) {
			this.label = label(key);
			String type = meta.get("type").getAsString();
			String value = meta.get("value").getAsString();
			switch (type) {
				case "BOOLEAN" -> {
					this.widgetWidth = 60;
					this.widget = CycleButton.onOffBuilder(Boolean.parseBoolean(value)).displayOnlyValue()
							.create(0, 0, this.widgetWidth, 20, this.label, (b, v) -> ConfigScreen.this.changes.put(key, String.valueOf(v)));
				}
				case "ENUM" -> {
					List<String> options = new ArrayList<>();
					meta.getAsJsonArray("options").forEach(o -> options.add(o.getAsString()));
					this.widgetWidth = 110;
					this.widget = CycleButton.builder(Component::literal, value).withValues(options).displayOnlyValue()
							.create(0, 0, this.widgetWidth, 20, this.label, (b, v) -> ConfigScreen.this.changes.put(key, v));
				}
				default -> {
					boolean wide = type.equals("STRING") || type.equals("LIST");
					this.widgetWidth = wide ? 170 : 70;
					EditBox box = new EditBox(ConfigScreen.this.font, 0, 0, this.widgetWidth, 20, this.label);
					box.setMaxLength(4096);
					box.setValue(value);
					box.setResponder(v -> {
						boolean valid = isValid(type, v, meta);
						box.setTextColor(valid ? TEXT_COLOR : ERROR_COLOR);
						if (valid && !v.equals(value)) ConfigScreen.this.changes.put(key, v);
						else ConfigScreen.this.changes.remove(key);
					});
					this.widget = box;
				}
			}
		}

		private static boolean isValid(String type, String v, JsonObject meta) {
			try {
				double d;
				switch (type) {
					case "INT", "LONG" -> d = Long.parseLong(v.trim());
					case "DOUBLE" -> d = Double.parseDouble(v.trim());
					default -> {
						return true;
					}
				}
				if (meta.has("min") && d < meta.get("min").getAsDouble()) return false;
				return !meta.has("max") || d <= meta.get("max").getAsDouble();
			} catch (NumberFormatException e) {
				return false;
			}
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
			graphics.text(ConfigScreen.this.font, this.label, this.getContentX(), this.getContentY() + 6, 0xFFFFFFFF);
			this.widget.setX(this.getContentRight() - this.widgetWidth);
			this.widget.setY(this.getContentY());
			this.widget.extractRenderState(graphics, mouseX, mouseY, a);
		}

		@Override
		public List<? extends GuiEventListener> children() {
			return List.of(this.widget);
		}

		@Override
		public List<? extends NarratableEntry> narratables() {
			return List.of(this.widget);
		}
	}
}
