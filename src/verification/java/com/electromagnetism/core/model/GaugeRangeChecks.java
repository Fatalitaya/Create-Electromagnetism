package com.electromagnetism.core.model;

public final class GaugeRangeChecks {
    public static void main(String[] args) {
        for (String unit : new String[]{"V", "A", "W", "Wh", "Hz"}) {
            for (double value : new double[]{0, 0.000001, 0.001, 0.999, 1, 999.999, 1000, 999999, 1e6, -0.5, -1234.567}) {
                var reading = MeterUnits.automatic(value, unit);
                double factor = reading.unit().equals("m" + unit) ? 0.001
                        : reading.unit().equals("k" + unit) ? 1000
                        : reading.unit().equals("M" + unit) ? 1e6 : 1;
                near(reading.value() * factor, value);
            }
        }
        unit(0.001, "A", 1, "mA");
        unit(1000, "W", 1, "kW");
        unit(1000, "Wh", 1, "kWh");
        unit(1e6, "Wh", 1, "MWh");
        unit(1e6, "V", 1000, "kV");
        unit(1e6, "A", 1000, "kA");
        unit(-1234.567, "W", -1.234567, "kW");
        double[] scales = {0.001, 0.01, 0.1, 1, 10, 100, 1000};
        String[] units = {"mV", "mV", "mV", "V", "V", "V", "kV"};
        for (int i = 0; i < scales.length; i++) {
            double scale = scales[i];
            if (!GaugeRange.unit(true, scale).equals(units[i])) throw new AssertionError("unit");
            for (boolean voltage : new boolean[]{true, false}) {
                double full = GaugeRange.fullScale(voltage, scale);
                near(GaugeRange.dial(full / 2, voltage, scale), 0.5);
                near(GaugeRange.dial(-full / 2, voltage, scale), 0.5);
                near(GaugeRange.dial(full * 2, voltage, scale), 1);
                near(GaugeRange.dial(0, voltage, scale), 0);
                // Unit conversion preserves the physical reading, independently of range.
                near(GaugeRange.displayed(12.345, scale) * GaugeRange.unitSize(scale), 12.345);
            }
        }
        near(GaugeRange.dial(500, true, 1), 0.5);
        near(GaugeRange.dial(500, true, 1000), 0.0005);
        near(GaugeRange.displayed(500, 1000), 0.5);
        near(GaugeRange.displayed(0.5, 0.001), 500);
        if (!GaugeRange.unit(false, 0.001).equals("mA")
                || !GaugeRange.unit(false, 1000).equals("kA")) throw new AssertionError("current unit");
        System.out.println("Gauge range checks passed: all ranges, unit invariance, polarity and saturation");
    }

    private static void near(double actual, double expected) {
        if (!Double.isFinite(actual) || !Double.isFinite(expected)
                || !(Math.abs(actual - expected) <= 1e-7))
            throw new AssertionError(actual + " != " + expected);
    }
    private static void unit(double value, String base, double expected, String expectedUnit) {
        var reading = MeterUnits.automatic(value, base);
        near(reading.value(), expected);
        if (!reading.unit().equals(expectedUnit)) throw new AssertionError(reading);
    }
}
