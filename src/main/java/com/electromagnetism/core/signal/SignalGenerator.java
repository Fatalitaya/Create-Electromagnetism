package com.electromagnetism.core.signal;

/** Finite, bounded laboratory source settings. Amplitude is peak volts. */
public record SignalGenerator(Wave wave, double frequency, double amplitude, double offset, boolean enabled) {
    public enum Wave { SINE, SQUARE, TRIANGLE, DC }
    public SignalGenerator {
        if (wave == null) wave = Wave.SINE;
        frequency = bound(frequency, 0.1, 100, 2);
        amplitude = bound(amplitude, 0, 10, 1);
        offset = bound(offset, -10, 10, 0);
    }
    private static double bound(double v, double min, double max, double fallback) {
        return Double.isFinite(v) ? Math.clamp(v, min, max) : fallback;
    }


}
