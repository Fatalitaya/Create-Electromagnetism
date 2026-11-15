package com.electromagnetism.integration.cee.display;

import com.electromagnetism.core.model.MeterUnits;
import net.createmod.catnip.lang.LangNumberFormat;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public final class MeterReading {
    private MeterReading() {}
    public static MutableComponent format(MeterUnits.Reading reading) {
        return Component.literal(LangNumberFormat.format(reading.value()) + " " + reading.unit());
    }
    public static MutableComponent automatic(double value, String unit) {
        if (!Double.isFinite(value)) return Component.literal("-- " + unit);
        return format(MeterUnits.automatic(value, unit));
    }
    public static MutableComponent frequency(double value) {
        if (!Double.isFinite(value) || value < 0) return Component.literal("-- Hz");
        var reading = MeterUnits.automatic(value, "Hz");
        return Component.literal(String.format(java.util.Locale.ROOT, "%.3f %s", reading.value(), reading.unit()));
    }
}
