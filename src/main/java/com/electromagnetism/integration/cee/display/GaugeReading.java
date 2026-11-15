package com.electromagnetism.integration.cee.display;

import com.electromagnetism.core.model.GaugeRange;
import com.george_vi.electroenergetics.content.gauge.ElectricGaugeBlockEntity;
import net.createmod.catnip.lang.LangNumberFormat;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public final class GaugeReading {
    private GaugeReading() {}

    public static double measurement(ElectricGaugeBlockEntity gauge) {
        return Math.abs(gauge.voltmeter ? gauge.voltage : gauge.voltage / 0.01);
    }

    public static MutableComponent format(ElectricGaugeBlockEntity gauge) {
        if(!Double.isFinite(gauge.voltage))return Component.translatable("electromagnetism.simulation.unavailable");
        double scale = gauge.scaling.getScale();
        return Component.literal(LangNumberFormat.format(GaugeRange.displayed(measurement(gauge), scale))
                + " " + GaugeRange.unit(gauge.voltmeter, scale));
    }
}
