package com.electromagnetism.integration.cee.nativebridge;
import java.util.UUID;
public interface NativeRotorBoundary extends CeeMechanicalSource {
    UUID electromagnetism$rotorIdentity();
    String electromagnetism$rotorProblem();
    double electromagnetism$permanentExcitation();
    void electromagnetism$torque(double torqueNewtonMetres,boolean available);
}
