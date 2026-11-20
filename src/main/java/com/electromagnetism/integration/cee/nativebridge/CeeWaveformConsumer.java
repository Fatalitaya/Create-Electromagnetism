package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.SimulationProtocol.Waveform;

/**
 * Receive waveforms on the world thread after checking session, revision and instance. Rebinding a
 * subscription requires a new channel UUID.
 */
public interface CeeWaveformConsumer {
    void electromagnetism$publishWaveform(Waveform waveform);
}
