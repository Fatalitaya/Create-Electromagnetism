package com.electromagnetism.integration.cee.mixin;

import com.george_vi.electroenergetics.content.electric_motor.ElectricMotorBlockEntity;
import com.george_vi.electroenergetics.foundation.RMSHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ElectricMotorBlockEntity.class, remap = false)
public interface ElectricMotorReadingAccessor {
    @Accessor("averageVoltage")
    RMSHolder electromagnetism$averageVoltage();
}
