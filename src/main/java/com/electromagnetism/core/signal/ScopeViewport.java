package com.electromagnetism.core.signal;
import com.electromagnetism.core.model.Numbers;
/** Time is relative to the latest sample, keeping the display offset independent of uptime. */
public record ScopeViewport(double voltsPerDivision, double secondsPerDivision,
                            double voltageCenter, double timeOffsetSeconds) {
    public ScopeViewport {
        Numbers.positive("volts/div", voltsPerDivision);
        Numbers.positive("seconds/div", secondsPerDivision);
        Numbers.finite("voltage center", voltageCenter);
        Numbers.finite("time offset", timeOffsetSeconds);
    }
    public double relativeTime(double fraction) { return (fraction - 1) * 10 * secondsPerDivision + timeOffsetSeconds; }
    public double horizontalFraction(double sampleTime, double referenceTime) {
        return 1 + ((sampleTime - referenceTime) - timeOffsetSeconds) / (10 * secondsPerDivision);
    }
    public double verticalFraction(double voltage) { return 0.5 - (voltage - voltageCenter) / (8 * voltsPerDivision); }
    /** Fit the timebase and pan to the captured history while leaving room for adjacent samples. */
    public ScopeViewport fitHistory(double historySeconds, double minimumSpanSeconds) {
        Numbers.positive("history", historySeconds);
        Numbers.positive("minimum span", minimumSpanSeconds);
        double span = Math.clamp(10 * secondsPerDivision, Math.min(minimumSpanSeconds, historySeconds), historySeconds);
        double offset = Math.clamp(timeOffsetSeconds, span - historySeconds, 0);
        return new ScopeViewport(voltsPerDivision, span / 10, voltageCenter, offset);
    }

}
