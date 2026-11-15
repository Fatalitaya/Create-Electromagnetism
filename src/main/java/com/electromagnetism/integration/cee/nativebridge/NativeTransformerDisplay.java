package com.electromagnetism.integration.cee.nativebridge;
/** Accepted-window RMS and real through power, including explicit unavailable state. */
public interface NativeTransformerDisplay {
    void electromagnetism$acceptTransformer(double primaryVolts,double secondaryVolts,double watts);
    void electromagnetism$transformerUnavailable();
}
