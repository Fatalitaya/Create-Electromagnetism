package com.electromagnetism.integration.cee.nativebridge;
public interface NativeTrainBoundary {
    void electromagnetism$traction(java.util.UUID session,double start,double end,double forceNewtons,long tick);
    void electromagnetism$tractionUnavailable();
}
