package io.github.babygipsn.deathswap.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.babygipsn.deathswap.world.RoundWorld;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Lets end gateways in a round's end generate their exit like in the real end. */
@Mixin(TheEndGatewayBlockEntity.class)
public class TheEndGatewayBlockEntityMixin {
	@WrapOperation(method = "getPortalPosition", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;dimension()Lnet/minecraft/resources/ResourceKey;"))
	private ResourceKey<Level> deathswap$aliasDimension(ServerLevel level, Operation<ResourceKey<Level>> original) {
		return RoundWorld.aliasKey(level, original.call(level));
	}
}
