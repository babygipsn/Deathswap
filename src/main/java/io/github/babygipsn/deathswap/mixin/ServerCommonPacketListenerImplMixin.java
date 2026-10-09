package io.github.babygipsn.deathswap.mixin;

import io.github.babygipsn.deathswap.pack.ResourcePackManager;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tracks whether vanilla clients loaded the HUD pack (only then is the font HUD used). */
@Mixin(ServerCommonPacketListenerImpl.class)
public class ServerCommonPacketListenerImplMixin {
	@Inject(method = "handleResourcePackResponse", at = @At("TAIL"))
	private void deathswap$packResponse(ServerboundResourcePackPacket packet, CallbackInfo ci) {
		// TAIL is only reached on the server thread (the first call re-schedules itself there)
		if ((Object) this instanceof ServerGamePacketListenerImpl game) {
			ResourcePackManager.onResponse(game.player, packet);
		}
	}
}
