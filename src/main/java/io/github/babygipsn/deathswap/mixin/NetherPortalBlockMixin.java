package io.github.babygipsn.deathswap.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.babygipsn.deathswap.world.RoundWorld;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.NetherPortalBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Links nether portals inside a round world to that round's nether (and back). */
@Mixin(NetherPortalBlock.class)
public class NetherPortalBlockMixin {
	@WrapOperation(method = "getPortalDestination", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;dimension()Lnet/minecraft/resources/ResourceKey;"))
	private ResourceKey<Level> deathswap$aliasDimension(ServerLevel level, Operation<ResourceKey<Level>> original) {
		return RoundWorld.aliasKey(level, original.call(level));
	}

	@WrapOperation(method = "getPortalDestination", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;getLevel(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/server/level/ServerLevel;"))
	private ServerLevel deathswap$roundLevel(MinecraftServer server, ResourceKey<Level> key, Operation<ServerLevel> original, @Local(argsOnly = true) ServerLevel currentLevel) {
		return RoundWorld.resolveLinked(currentLevel, key, () -> original.call(server, key));
	}
}
