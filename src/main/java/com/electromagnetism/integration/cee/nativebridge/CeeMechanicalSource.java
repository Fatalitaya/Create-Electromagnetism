package com.electromagnetism.integration.cee.nativebridge;

/**
 * Capture Create mechanical inputs and explicitly convert RPM and stress to SI quantities at the
 * adapter boundary.
 */
public interface CeeMechanicalSource {
    float electromagnetism$shaftSpeedRpm();
}
