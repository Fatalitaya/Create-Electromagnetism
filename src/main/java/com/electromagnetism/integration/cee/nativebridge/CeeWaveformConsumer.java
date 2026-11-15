package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.SimulationProtocol.Waveform;

/** Receives an already version/instance-validated waveform on the world thread.
 * The channel UUID must change when the subscription/session binding changes.
 */
public interface CeeWaveformConsumer {
    void electromagnetism$publishWaveform(Waveform waveform);
}
