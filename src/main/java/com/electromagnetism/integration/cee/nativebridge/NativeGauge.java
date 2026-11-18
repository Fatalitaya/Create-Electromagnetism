package com.electromagnetism.integration.cee.nativebridge;
import com.electromagnetism.simulation.SimulationProtocol.Reading;
public interface NativeGauge {
    double electromagnetism$measurement();
    void electromagnetism$acceptGauge(Reading reading);
    void electromagnetism$gaugeUnavailable();
}
