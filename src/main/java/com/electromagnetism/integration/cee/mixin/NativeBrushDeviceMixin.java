package com.electromagnetism.integration.cee.mixin;

import com.electromagnetism.integration.cee.nativebridge.*;
import com.electromagnetism.simulation.SimulationProtocol.*;
import com.george_vi.electroenergetics.content.rotor.*;
import com.george_vi.electroenergetics.devices.device.SimulatedDevice;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.Map;
import java.util.stream.IntStream;

/** Source facts and opaque migration data, not an AC model or a second phase/energy owner. */
@Mixin(value = {AlternatorBrushesDevice.class, ThreePhaseAlternatorBrushesDevice.class}, remap = false)
public abstract class NativeBrushDeviceMixin implements CeeDeviceSource {
    @Unique private CompoundTag electromagnetism$legacy = new CompoundTag();
    @Override public DeviceInput electromagnetism$captureInput() {
        var self = (SimulatedDevice)(Object)this;
        var id = ((CeeDeviceIdentity)this).electromagnetism$nativeIdentity();
        int phases = self instanceof ThreePhaseAlternatorBrushesDevice ? 3 : 1;
        if (!self.level.isLoaded(self.pos)
                || !(self.level.getBlockEntity(self.pos) instanceof CeeMechanicalSource source))
            throw new IllegalStateException("Brush mechanical boundary is not loaded");
        return new DeviceInput(id, self.type.id().toString(),
                IntStream.range(0, phases + 1).mapToObj(i -> new PortId(id, i)).toList(),
                Map.of("phaseCount", (double)phases, "shaftAngularVelocityRadiansPerSecond",
                        source.electromagnetism$shaftSpeedRpm() * (2 * Math.PI / 60)));
    }
    @Inject(method = "read", at = @At("HEAD"))
    private void electromagnetism$preserveLegacy(CompoundTag tag, CallbackInfo ci) {
        electromagnetism$legacy = tag.copy();
    }
    @Inject(method = "write", at = @At("HEAD"))
    private void electromagnetism$saveLegacy(CompoundTag tag, CallbackInfo ci) {
        // Upstream writes its own current fields afterwards; custom Ac* fields survive unchanged.
        tag.merge(electromagnetism$legacy.copy());
    }
}
