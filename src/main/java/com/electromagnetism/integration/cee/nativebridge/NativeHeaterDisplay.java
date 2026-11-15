package com.electromagnetism.integration.cee.nativebridge;
import com.electromagnetism.simulation.SimulationProtocol.Reading;
public interface NativeHeaterDisplay {
    void electromagnetism$acceptHeat(Reading reading);
    void electromagnetism$heatUnavailable();
}
