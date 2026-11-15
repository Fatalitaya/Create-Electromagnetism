package com.electromagnetism.integration.cee.mixin;

import com.electromagnetism.integration.cee.display.GaugeReading;
import com.george_vi.electroenergetics.content.gauge.AmperageDisplaySource;
import com.george_vi.electroenergetics.content.gauge.ElectricGaugeBlockEntity;
import com.george_vi.electroenergetics.content.gauge.VoltageDisplaySource;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = {VoltageDisplaySource.class, AmperageDisplaySource.class}, remap = false)
public abstract class GaugeDisplaySourceMixin {
    @Inject(method = "provideLine", at = @At("HEAD"), cancellable = true)
    private void electromagnetism$reading(DisplayLinkContext context, DisplayTargetStats stats,
                                         CallbackInfoReturnable<MutableComponent> cir) {
        if (context.getSourceBlockEntity() instanceof ElectricGaugeBlockEntity gauge
                && gauge.voltmeter == ((Object) this instanceof VoltageDisplaySource))
            cir.setReturnValue(GaugeReading.format(gauge));
    }
}
