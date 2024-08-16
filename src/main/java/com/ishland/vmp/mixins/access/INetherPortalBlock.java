package com.ishland.vmp.mixins.access;

import net.minecraft.block.NetherPortalBlock;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockLocating;
import net.minecraft.world.TeleportTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(NetherPortalBlock.class)
public interface INetherPortalBlock {

    @Invoker
    static TeleportTarget invokeGetExitPortalTarget(Entity entity, BlockPos pos, BlockLocating.Rectangle exitPortalRectangle, ServerWorld world, TeleportTarget.class_9823 arg) {
        throw new AssertionError();
    }

}
