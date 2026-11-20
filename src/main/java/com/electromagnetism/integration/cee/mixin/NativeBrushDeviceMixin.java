package com.electromagnetism.integration.cee.mixin;

import com.george_vi.electroenergetics.content.rotor.*;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Retain brush inputs and existing save data. The native session advances phase and energy. */
@Mixin(value = {AlternatorBrushesDevice.class, ThreePhaseAlternatorBrushesDevice.class}, remap = false)
public abstract class NativeBrushDeviceMixin {
    @Unique private CompoundTag electromagnetism$legacy = new CompoundTag();
    @Inject(method = "read", at = @At("HEAD"))
    private void electromagnetism$preserveLegacy(CompoundTag tag, CallbackInfo ci) {
        electromagnetism$legacy = tag.copy();
    }
    @Inject(method = "write", at = @At("HEAD"))
    private void electromagnetism$saveLegacy(CompoundTag tag, CallbackInfo ci) {
        // CEE writes its own current fields later. The custom fields use the Ac prefix.
        tag.merge(electromagnetism$legacy.copy());
    }
}
