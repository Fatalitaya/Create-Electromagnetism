package com.electromagnetism.integration.cee.display;

import com.electromagnetism.integration.cee.mixin.FrequencyMeterBlockEntityAccessor;
import com.george_vi.electroenergetics.content.frequency_meter.FrequencyMeterBlockEntity;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.source.NumericSingleLineDisplaySource;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import net.minecraft.network.chat.MutableComponent;


/**
 * 频率表的显示连接器数据源：输出三位小数的当前频率和对应单位。
 * 与护目镜使用相同的单位、精度和无效读数格式。
 */
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
