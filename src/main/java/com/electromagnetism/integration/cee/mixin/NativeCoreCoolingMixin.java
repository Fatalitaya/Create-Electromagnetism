package com.electromagnetism.integration.cee.mixin;

import com.electromagnetism.integration.cee.nativebridge.NativeCoreCooling;
import com.george_vi.electroenergetics.content.transmission_distribution.transformer.TransformerCoreBlockEntity;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=TransformerCoreBlockEntity.class,remap=false)
public abstract class NativeCoreCoolingMixin implements NativeCoreCooling {
    @Shadow protected double heatDissipationFactor;
    @Override public void electromagnetism$coolingLimit(double watts){heatDissipationFactor=watts;}
    @Inject(method="lazyTick",at=@At("HEAD"),cancellable=true)
    private void electromagnetism$nativeCooling(CallbackInfo ci){ci.cancel();}
}
