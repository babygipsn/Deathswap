package io.github.babygipsn.deathswap.menu;

import io.github.babygipsn.deathswap.Deathswap;
import io.github.babygipsn.deathswap.config.ConfigManager;
import io.github.babygipsn.deathswap.game.Fx;
import io.github.babygipsn.deathswap.game.GameManager;
import io.github.babygipsn.deathswap.lang.Lang;
import io.github.babygipsn.deathswap.util.Perms;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side chest GUI for editing the config (works for vanilla clients). Items are display-only:
 * every click is intercepted and the client is resynced.
 * Left click = +step / toggle / next, right click = -step / previous, shift = x10.
 */
public final class ConfigMenu extends ChestMenu {
	private static final int ROWS = 6;
	private static final int SIZE = ROWS * 9;
	private static final int SLOT_BACK = 45;
	private static final int SLOT_CLOSE = 49;

	private final ServerPlayer viewer;
	private final SimpleContainer container;
	private final Map<Integer, Runnable> leftActions = new HashMap<>();
	private final Map<Integer, Runnable> rightActions = new HashMap<>();
	private @Nullable String category;
	private boolean shift;

	private ConfigMenu(int id, Inventory inventory, SimpleContainer container, ServerPlayer viewer) {
		super(MenuType.GENERIC_9x6, id, inventory, container, ROWS);
		this.container = container;
		this.viewer = viewer;
		this.render();
	}

	public static void open(ServerPlayer player) {
		player.openMenu(new SimpleMenuProvider((id, inv, p) -> new ConfigMenu(id, inv, new SimpleContainer(SIZE), player),
				Lang.tr(player, "deathswap.menu.title")));
	}

	// ------------------------------------------------------------------ rendering

	private void render() {
		this.container.clearContent();
		this.leftActions.clear();
		this.rightActions.clear();
		ItemStack filler = named(item("minecraft:gray_stained_glass_pane"), Component.literal(" "), List.of());
		for (int i = 45; i < SIZE; i++) this.container.setItem(i, filler.copy());

		if (this.category == null) this.renderHome();
		else this.renderCategory(this.category);
		this.broadcastChanges();
	}

	private void renderHome() {
		List<String> categories = ConfigManager.categories();
		int slot = 10;
		for (String cat : categories) {
			String c = cat;
			var entries = ConfigManager.byCategory().get(cat);
			Item icon = item(entries.isEmpty() ? "minecraft:book" : entries.getFirst().icon());
			this.set(slot, named(icon, tr("deathswap.menu.category." + cat).withStyle(ChatFormatting.GOLD),
					List.of(tr("deathswap.menu.open_category").withStyle(ChatFormatting.GRAY))), () -> this.go(c), () -> this.go(c));
			slot++;
			if (slot % 9 == 8) slot += 2;
		}

		GameManager game = GameManager.get();
		this.set(37, named(item("minecraft:lime_concrete"), tr("deathswap.menu.start").withStyle(ChatFormatting.GREEN),
				List.of(tr("deathswap.menu.start_desc").withStyle(ChatFormatting.GRAY))), () -> {
			String err = game.requestStart(true);
			if (err != null) Fx.msg(this.viewer, err);
			this.viewer.closeContainer();
		}, null);
		this.set(39, named(Items.ENDER_PEARL, tr("deathswap.menu.swap").withStyle(ChatFormatting.LIGHT_PURPLE), List.of()), () -> {
			if (game.state() == GameManager.State.RUNNING) game.swap();
			else Fx.msg(this.viewer, "deathswap.err.not_running");
		}, null);
		this.set(41, named(Items.ANVIL, tr("deathswap.menu.reload").withStyle(ChatFormatting.AQUA), List.of()), () -> {
			ConfigManager.load();
			Deathswap.onConfigChanged();
			Fx.msg(this.viewer, "deathswap.msg.config_reloaded");
		}, null);
		this.set(43, named(item("minecraft:red_concrete"), tr("deathswap.menu.stop").withStyle(ChatFormatting.RED), List.of()), () -> {
			game.stop(true);
			this.viewer.closeContainer();
		}, null);
		this.set(SLOT_CLOSE, named(Items.BARRIER, tr("deathswap.menu.close").withStyle(ChatFormatting.RED), List.of()),
				this.viewer::closeContainer, null);
	}

	private void renderCategory(String cat) {
		List<ConfigManager.Entry> entries = ConfigManager.byCategory().getOrDefault(cat, List.of());
		int slot = 0;
		for (ConfigManager.Entry e : entries) {
			if (slot >= 45) break;
			List<Component> lore = new ArrayList<>();
			lore.add(tr("deathswap.menu.value", e.getAsString()).withStyle(ChatFormatting.WHITE));
			String descKey = "deathswap.config." + e.key + ".desc";
			if (!Lang.raw(this.viewer, descKey).equals(descKey)) lore.add(tr(descKey).withStyle(ChatFormatting.DARK_GRAY));
			lore.add(Component.empty());
			switch (e.kind()) {
				case BOOLEAN -> lore.add(tr("deathswap.menu.hint_toggle").withStyle(ChatFormatting.YELLOW));
				case INT, LONG, DOUBLE -> {
					String step = e.step() == Math.rint(e.step()) ? String.valueOf((long) e.step()) : String.valueOf(e.step());
					lore.add(tr("deathswap.menu.hint_number", step, step).withStyle(ChatFormatting.YELLOW));
					lore.add(tr("deathswap.menu.hint_shift").withStyle(ChatFormatting.YELLOW));
				}
				case ENUM -> lore.add(tr("deathswap.menu.hint_cycle").withStyle(ChatFormatting.YELLOW));
				default -> lore.add(tr("deathswap.menu.hint_command", e.key).withStyle(ChatFormatting.YELLOW));
			}
			Item icon = e.kind() == ConfigManager.Kind.BOOLEAN
					? item((Boolean) e.getRaw() ? "minecraft:lime_dye" : "minecraft:gray_dye")
					: item(e.icon());
			ItemStack stack = named(icon, Component.literal(label(e)).withStyle(ChatFormatting.AQUA), lore);
			this.set(slot++, stack, () -> this.change(e, 1), () -> this.change(e, -1));
		}
		this.set(SLOT_BACK, named(Items.ARROW, tr("deathswap.menu.back").withStyle(ChatFormatting.GRAY), List.of()), () -> this.go(null), null);
		this.set(SLOT_CLOSE, named(Items.BARRIER, tr("deathswap.menu.close").withStyle(ChatFormatting.RED), List.of()),
				this.viewer::closeContainer, null);
	}

	private String label(ConfigManager.Entry e) {
		String key = "deathswap.config." + e.key;
		String raw = Lang.raw(this.viewer, key);
		return raw.equals(key) ? e.name() : raw;
	}

	private void change(ConfigManager.Entry e, int direction) {
		switch (e.kind()) {
			case BOOLEAN, ENUM -> e.nudge(direction);
			case INT, LONG, DOUBLE -> e.nudge(direction * (this.shift ? 10 : 1));
			default -> {
				Fx.msg(this.viewer, "deathswap.menu.hint_command", e.key);
				return;
			}
		}
		Deathswap.onConfigChanged();
		Fx.sound(this.viewer, SoundEvents.UI_BUTTON_CLICK, 0.4f, 1.2f);
	}

	private void go(@Nullable String cat) {
		this.category = cat;
	}

	private void set(int slot, ItemStack stack, @Nullable Runnable left, @Nullable Runnable right) {
		this.container.setItem(slot, stack);
		if (left != null) this.leftActions.put(slot, left);
		if (right != null) this.rightActions.put(slot, right);
	}

	private MutableComponent tr(String key, Object... args) {
		return Lang.tr(this.viewer, key, args);
	}

	private static Item item(String id) {
		Identifier identifier = Identifier.tryParse(id);
		Item item = identifier == null ? Items.PAPER : BuiltInRegistries.ITEM.getValue(identifier);
		return item == Items.AIR ? Items.PAPER : item;
	}

	private static ItemStack named(Item item, Component name, List<Component> lore) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.CUSTOM_NAME, name.copy().withStyle(s -> s.withItalic(false)));
		if (!lore.isEmpty()) {
			stack.set(DataComponents.LORE, new ItemLore(lore.stream().map(c -> (Component) c.copy().withStyle(s -> s.withItalic(false))).toList()));
		}
		return stack;
	}

	// ------------------------------------------------------------------ input

	@Override
	public void clicked(int slotIndex, int buttonNum, ContainerInput input, Player player) {
		if (slotIndex >= 0 && slotIndex < SIZE && Perms.isAdmin(this.viewer)) {
			this.shift = input == ContainerInput.QUICK_MOVE;
			boolean right = buttonNum == 1;
			Runnable action = right ? this.rightActions.getOrDefault(slotIndex, this.leftActions.get(slotIndex)) : this.leftActions.get(slotIndex);
			if (action != null && (input == ContainerInput.PICKUP || input == ContainerInput.QUICK_MOVE)) {
				action.run();
				if (this.viewer.containerMenu == this) this.render();
			}
		}
		// undo whatever the client predicted
		this.setCarried(ItemStack.EMPTY);
		this.sendAllDataToRemote();
	}

	@Override
	public ItemStack quickMoveStack(Player player, int slotIndex) {
		return ItemStack.EMPTY;
	}

	@Override
	public boolean stillValid(Player player) {
		return true;
	}
}
