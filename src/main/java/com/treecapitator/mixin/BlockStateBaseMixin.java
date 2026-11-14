package com.treecapitator.mixin;

import com.treecapitator.TreeCapitatorHandler;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applies the server-side felling delay to the block being mined.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseMixin {
    @Inject(
            method = "getDestroyProgress(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)F",
            at = @At("RETURN"),
            cancellable = true
    )
    private void treecapitator$adjustDestroyProgress(Player player, BlockGetter level, BlockPos pos,
            CallbackInfoReturnable<Float> callbackInfo) {
        float original = callbackInfo.getReturnValue();
        float adjusted = TreeCapitatorHandler.adjustDestroyProgress(
                (BlockState) (Object) this, player, level, pos, original);

        if (adjusted != original) {
            callbackInfo.setReturnValue(adjusted);
        }
    }
}
