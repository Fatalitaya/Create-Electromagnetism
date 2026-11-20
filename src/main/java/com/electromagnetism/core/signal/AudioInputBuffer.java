package com.electromagnetism.core.signal;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;

/** Each buffer carries one audio stream. On overflow, discard the oldest whole frame. */
public final class AudioInputBuffer {
    private final SampleClock clock;
    private final int capacity;
    private final ArrayDeque<AudioFrame> frames = new ArrayDeque<>();
    private long nextSample;
    private long droppedSamples;
    private long missingSamples;

    public AudioInputBuffer(SampleClock clock, int frameCapacity) {
        this.clock = Objects.requireNonNull(clock);
        if (frameCapacity < 1 || frameCapacity > 1024)
            throw new IllegalArgumentException("Invalid frame capacity");
        capacity = frameCapacity;
    }

    public synchronized void offer(AudioFrame frame) {
        Objects.requireNonNull(frame);
        if (!clock.equals(frame.clock()) || frame.firstSample() < nextSample)
            throw new IllegalArgumentException("Wrong clock, duplicate or overlapping frame");
        missingSamples += frame.firstSample() - nextSample;
        nextSample = frame.endSample();
        if (frames.size() == capacity) droppedSamples += frames.removeFirst().size();
        frames.addLast(frame);
    }

    /** An empty result means missing input. Silence requires an actual frame of zero samples. */
    public synchronized Optional<AudioFrame> poll() { return Optional.ofNullable(frames.pollFirst()); }
    public synchronized int size() { return frames.size(); }
    public synchronized long droppedSamples() { return droppedSamples; }
    public synchronized long missingSamples() { return missingSamples; }
}
