package com.electromagnetism.integration.cee.mixin;

import com.george_vi.electroenergetics.content.bundled_wire.BundledWireNodeConfigurator;
import com.george_vi.electroenergetics.foundation.nodes.InWorldNode;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value=BundledWireNodeConfigurator.class,remap=false)
public abstract class NativeBundledWireNodesMixin {
    @Inject(method="getWireNodeFor",at=@At("HEAD"),cancellable=true)
    private static void electromagnetism$terminal(BlockPos pos,boolean roll,boolean flip,int conductor,CallbackInfoReturnable<InWorldNode> ci) {
        if(conductor<0 || conductor>2)throw new IllegalArgumentException("Duplex conductor index must be 0..2");
        ci.setReturnValue(new InWorldNode(4096 | (((flip?2:0)+(roll?1:0))<<4) | conductor,pos));
    }
}
