package com.electromagnetism.core.signal;

import com.electromagnetism.core.model.Numbers;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;

/** Each buffer holds timestamped samples for one channel and session. Snapshots are immutable. */
public final class WaveformBuffer {
    public enum Quality { VALID, STALE, UNCONVERGED, MISSING, RECONSTRUCTED, BANDWIDTH_LIMIT; public boolean hasValue(){return this==VALID||this==RECONSTRUCTED;} }

    public record Sample(double timeSeconds, OptionalDouble value, Quality quality) {
        public Sample {
            Numbers.nonNegative("sample time", timeSeconds);
            Objects.requireNonNull(value);
            Objects.requireNonNull(quality);
            if (value.isPresent()) Numbers.finite("sample", value.getAsDouble());
            if (quality.hasValue() != value.isPresent())
                throw new IllegalArgumentException("Only valid observations carry a value");
        }
        public static Sample valid(double time, double value) {
            return new Sample(time, OptionalDouble.of(value), Quality.VALID);
        }
    }

    private final Sample[] ring;
    private int start;
    private int size;
    private long overwritten;

    public WaveformBuffer(int capacity) {
        if (capacity < 2 || capacity > 1048576) throw new IllegalArgumentException("Invalid capacity");
        ring = new Sample[capacity];
    }

    public synchronized void append(Sample sample) {
        Objects.requireNonNull(sample);
        if (size > 0 && sample.timeSeconds() <= ring[(start + size - 1) % ring.length].timeSeconds())
            throw new IllegalArgumentException("Samples must advance in time");
        if (size == ring.length) {
            start = (start + 1) % ring.length;
            size--;
            overwritten++;
        }
        ring[(start + size++) % ring.length] = sample;
    }

    public synchronized List<Sample> snapshot() {
        List<Sample> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) result.add(ring[(start + i) % ring.length]);
        return List.copyOf(result);
    }

    public synchronized long overwrittenSamples() { return overwritten; }
}
