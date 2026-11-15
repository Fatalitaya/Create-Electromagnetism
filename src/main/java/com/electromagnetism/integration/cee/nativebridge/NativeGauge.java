package com.electromagnetism.integration.cee.nativebridge;
import com.electromagnetism.simulation.SimulationProtocol.Reading;
public interface NativeGauge {
    void electromagnetism$acceptGauge(Reading reading);
    void electromagnetism$gaugeUnavailable();
}
