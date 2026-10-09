package io.github.babygipsn.deathswap.util;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

/** Operator checks in one place (the permission API differs between game versions). */
public final class Perms {
	private Perms() {
	}

	public static boolean isAdmin(ServerPlayer player) {
		return player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	public static boolean isAdmin(CommandSourceStack source) {
		return source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}
}
