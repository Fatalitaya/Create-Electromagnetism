package com.electromagnetism.integration.cee.display;

import com.electromagnetism.integration.cee.mixin.FrequencyMeterBlockEntityAccessor;
import com.george_vi.electroenergetics.content.frequency_meter.FrequencyMeterBlockEntity;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.source.NumericSingleLineDisplaySource;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import net.minecraft.network.chat.MutableComponent;


/** The display link and goggles share frequency readings and unit formatting. */
public class FrequencyMeterDisplaySource extends NumericSingleLineDisplaySource {
    @Override
    protected MutableComponent provideLine(DisplayLinkContext context, DisplayTargetStats stats) {
        if (!(context.getSourceBlockEntity() instanceof FrequencyMeterBlockEntity be))
            return ZERO.copy();
        float frequency = ((FrequencyMeterBlockEntityAccessor) be).electromagnetism$getFrequency();
        return MeterReading.frequency(frequency);
    }

    @Override
    protected boolean allowsLabeling(DisplayLinkContext context) {
        return true;
    }
}
