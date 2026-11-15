package com.electromagnetism.integration.cee.nativebridge;

/** Raw Create input. RPM/stress conversion to SI is a separately versioned contract, not implicit. */
public interface CeeMechanicalSource {
    float electromagnetism$shaftSpeedRpm();
}
