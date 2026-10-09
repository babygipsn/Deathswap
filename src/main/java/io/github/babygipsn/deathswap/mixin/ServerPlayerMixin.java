package io.github.babygipsn.deathswap.mixin;

import io.github.babygipsn.deathswap.game.GameManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Participants respawn inside the current round world instead of the lobby. */
@Mixin(ServerPlayer.class)
public class ServerPlayerMixin {
	@Inject(method = "findRespawnPositionAndUseSpawnBlock", at = @At("RETURN"), cancellable = true)
	private void deathswap$roundRespawn(boolean consumeSpawnBlock, TeleportTransition.PostTeleportTransition post, CallbackInfoReturnable<TeleportTransition> cir) {
		GameManager game = GameManager.getOrNull();
		if (game == null) return;
		TeleportTransition override = game.respawnOverride((ServerPlayer) (Object) this, cir.getReturnValue());
		if (override != null) cir.setReturnValue(override);
	}
}
