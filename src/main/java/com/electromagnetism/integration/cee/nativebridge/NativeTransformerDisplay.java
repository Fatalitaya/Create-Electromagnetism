package com.electromagnetism.integration.cee.nativebridge;
/** Return RMS and active power for the accepted window, representing missing readings explicitly. */
public interface NativeTransformerDisplay {
    void electromagnetism$acceptTransformer(double primaryVolts,double secondaryVolts,double watts);
    void electromagnetism$transformerUnavailable();
}
