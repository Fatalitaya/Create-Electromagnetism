package com.electromagnetism.integration.cee.nativebridge;
import java.util.UUID;
public interface CeeWireIdentity {
    UUID electromagnetism$wireIdentity();
    void electromagnetism$restoreWireIdentity(UUID identity);
    String electromagnetism$wireIdentityProblem();
    void electromagnetism$rejectWireIdentity(String reason);
}
