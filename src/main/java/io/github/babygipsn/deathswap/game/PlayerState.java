package io.github.babygipsn.deathswap.game;

import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Stats and round state of one participant. */
public final class PlayerState {
	public final UUID id;
	public String name;
	public int deaths;
	public int roundDeaths;
	public int trapKills;
	public final Map<String, Integer> causes = new LinkedHashMap<>();

	/** Where this player started the current round; used for respawning. */
	public @Nullable BlockPos spawn;
	/** Player whose position we were swapped to most recently (they built whatever is there). */
	public @Nullable UUID trapSetter;
	public long lastSwapTick = Long.MIN_VALUE / 2;
	public long graceUntilTick;

	public PlayerState(UUID id, String name) {
		this.id = id;
		this.name = name;
	}
}
