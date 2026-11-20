package com.electromagnetism.integration.cee.mixin;

import com.electromagnetism.integration.cee.display.MeterReading;
import com.george_vi.electroenergetics.foundation.CEELang;
import net.createmod.catnip.lang.LangBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Probes, wire overlays and energy meter items share number and unit formatting. */
@Mixin(value = CEELang.class, remap = false)
public abstract class ElectricalUnitsMixin {
    @Inject(method = "formatVoltage", at = @At("HEAD"), cancellable = true)
    private static void electromagnetism$voltage(double value, CallbackInfoReturnable<LangBuilder> cir) {
        cir.setReturnValue(CEELang.builder().add(MeterReading.automatic(value, "V")));
    }
    @Inject(method = "formatAmperage", at = @At("HEAD"), cancellable = true)
    private static void electromagnetism$current(double value, CallbackInfoReturnable<LangBuilder> cir) {
        cir.setReturnValue(CEELang.builder().add(MeterReading.automatic(value, "A")));
    }
    @Inject(method = "formatPower", at = @At("HEAD"), cancellable = true)
    private static void electromagnetism$power(double value, CallbackInfoReturnable<LangBuilder> cir) {
        cir.setReturnValue(CEELang.builder().add(MeterReading.automatic(value, "W")));
    }
    @Inject(method = "formatEnergy", at = @At("HEAD"), cancellable = true)
    private static void electromagnetism$energy(double value, CallbackInfoReturnable<LangBuilder> cir) {
        cir.setReturnValue(CEELang.builder().add(MeterReading.automatic(value, "Wh")));
    }
}
