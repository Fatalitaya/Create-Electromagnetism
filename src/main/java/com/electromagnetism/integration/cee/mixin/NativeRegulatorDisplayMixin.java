package com.electromagnetism.integration.cee.mixin;

import com.electromagnetism.integration.cee.nativebridge.NativeRegulatorDisplay;
import com.george_vi.electroenergetics.content.transmission_distribution.voltage_regulator.VoltageRegulatorBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = VoltageRegulatorBlockEntity.class, remap = false)
public abstract class NativeRegulatorDisplayMixin implements NativeRegulatorDisplay {
    @Shadow protected double inputVoltage;
    @Shadow protected double outputVoltage;
    @Shadow protected double power;

    @Override
    public void electromagnetism$displayRegulator(double inputVolts, double outputVolts, double watts) {
        if (inputVoltage == inputVolts && outputVoltage == outputVolts && power == watts) return;
        inputVoltage = inputVolts;
        outputVoltage = outputVolts;
        power = watts;
        ((VoltageRegulatorBlockEntity)(Object)this).sendData();
    }
}
