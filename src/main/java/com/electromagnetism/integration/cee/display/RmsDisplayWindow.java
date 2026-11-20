package com.electromagnetism.integration.cee.display;

import com.electromagnetism.simulation.SimulationProtocol.Reading;

/** Combine branch readings from adjacent accepted windows for the instrument display. */
public final class RmsDisplayWindow {
    public record Sample(double end, double voltage, double current, double activePower) {}

    private static final double WINDOW_SECONDS = 5 * 0.05;
    private double start, end = Double.NaN;
    private double voltageSquaredSeconds, currentSquaredSeconds, joules;
    private Sample sample;
    private boolean hasCompleteWindow;

    public Sample sample() { return sample; }

    public Sample accept(Reading reading) {
        double from = reading.windowStart(), to = reading.windowEnd();
        if (to <= from || to == end) return sample;
        double tolerance = 4 * Math.ulp(to);
        if (!Double.isFinite(end) || Math.abs(from - end) > tolerance) {
            hasCompleteWindow = false;
            reset(from);
        }
        double dt = to - from;
        voltageSquaredSeconds += reading.rmsVoltage() * reading.rmsVoltage() * dt;
        currentSquaredSeconds += reading.rmsCurrent() * reading.rmsCurrent() * dt;
        joules += reading.activePower() * dt;
        end = to;
        double duration = end - start;
        if (duration + tolerance >= WINDOW_SECONDS) {
            sample = new Sample(end, Math.sqrt(voltageSquaredSeconds / duration),
                    Math.sqrt(currentSquaredSeconds / duration), joules / duration);
            hasCompleteWindow = true;
            reset(end);
        } else if (!hasCompleteWindow) {
            sample = new Sample(end, reading.rmsVoltage(), reading.rmsCurrent(), reading.activePower());
        }
        return sample;
    }

    private void reset(double from) {
        start = from;
        voltageSquaredSeconds = currentSquaredSeconds = joules = 0;
    }
}
