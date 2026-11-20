package com.electromagnetism.integration.cee.mixin;

import com.george_vi.electroenergetics.content.frequency_meter.FrequencyMeterBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The display link reads the unformatted frequency through this accessor. */
@Mixin(FrequencyMeterBlockEntity.class)
public interface FrequencyMeterBlockEntityAccessor {
    @Accessor(value="frequency",remap=false)
    void electromagnetism$setFrequency(float value);
    @Accessor("frequency")
    float electromagnetism$getFrequency();
}
