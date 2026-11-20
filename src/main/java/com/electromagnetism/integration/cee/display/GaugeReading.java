package com.electromagnetism.integration.cee.display;

import com.electromagnetism.core.model.GaugeRange;
import com.electromagnetism.integration.cee.nativebridge.NativeGauge;
import com.george_vi.electroenergetics.content.gauge.ElectricGaugeBlockEntity;
import com.electromagnetism.core.model.MeterUnits;
import net.minecraft.network.chat.MutableComponent;

public final class GaugeReading {
    private GaugeReading() {}

    public static double measurement(ElectricGaugeBlockEntity gauge) {
        return ((NativeGauge) gauge).electromagnetism$measurement();
    }

    public static MutableComponent format(ElectricGaugeBlockEntity gauge) {
        double value = measurement(gauge);
        if (!Double.isFinite(value)) return MeterReading.unavailable();
        double scale = gauge.scaling.getScale();
        return MeterReading.format(new MeterUnits.Reading(GaugeRange.displayed(value, scale),
                GaugeRange.unit(gauge.voltmeter, scale)));
    }
}
