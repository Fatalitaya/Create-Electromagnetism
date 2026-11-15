package com.electromagnetism.integration.cee.nativebridge;
public interface NativeMotorBoundary extends CeeMechanicalSource {
    void electromagnetism$motor(double meanTorque,double meanVolts,double activeWatts,boolean available);
}
