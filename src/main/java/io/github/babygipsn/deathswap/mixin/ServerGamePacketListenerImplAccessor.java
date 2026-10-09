package io.github.babygipsn.deathswap.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerGamePacketListenerImpl.class)
public interface ServerGamePacketListenerImplAccessor {
	/** What the vanilla respawn handler calls after a respawn; without it the player stays flagged as "waiting for respawn". */
	@Invoker("restartClientLoadTimerAfterRespawn")
	void deathswap$restartClientLoadTimerAfterRespawn();
}
