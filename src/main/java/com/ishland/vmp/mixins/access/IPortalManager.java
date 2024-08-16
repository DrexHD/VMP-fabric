package com.ishland.vmp.mixins.access;

import net.minecraft.block.Portal;
import net.minecraft.world.dimension.PortalManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PortalManager.class)
public interface IPortalManager {

    @Accessor
    Portal getPortal();

}
