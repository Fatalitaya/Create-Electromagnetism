package com.electromagnetism.integration.cee.nativebridge;
import com.electromagnetism.simulation.SimulationProtocol.Reading;
import com.electromagnetism.integration.cee.display.RmsDisplayWindow;
public interface NativeGauge {
    double electromagnetism$measurement();
    void electromagnetism$acceptGauge(Reading reading, RmsDisplayWindow.Sample rms);
    void electromagnetism$gaugeUnavailable();
}
