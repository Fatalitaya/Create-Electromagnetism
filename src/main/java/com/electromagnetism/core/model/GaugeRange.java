package com.electromagnetism.core.model;

/** Range multipliers change full scale, never the measured physical quantity. */
public final class GaugeRange {
    private GaugeRange() {}

    public static double fullScale(boolean voltage, double scale) {
        return (voltage ? 1000 : 40) * scale;
    }

    public static float dial(double measurement, boolean voltage, double scale) {
        return (float) Math.clamp(Math.abs(measurement) / fullScale(voltage, scale), 0, 1);
    }

    public static double unitSize(double scale) {
        return scale < 1 ? 0.001 : scale >= 1000 ? 1000 : 1;
    }

    public static double displayed(double measurement, double scale) {
        return measurement / unitSize(scale);
    }

    public static String unit(boolean voltage, double scale) {
        return (scale < 1 ? "m" : scale >= 1000 ? "k" : "") + (voltage ? "V" : "A");
    }
}
