package com.electromagnetism.integration.cee.nativebridge;
public interface NativeEnergyDisplay {
    void electromagnetism$acceptEnergy(double watts,double netJoules);
    void electromagnetism$energyUnavailable();
}
