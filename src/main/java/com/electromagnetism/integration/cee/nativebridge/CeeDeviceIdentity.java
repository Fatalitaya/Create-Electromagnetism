package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.SimulationProtocol.DeviceId;
import net.minecraft.nbt.Tag;

/** Persistent incarnation identity; ordinary device parameter reads never replace it. */
public interface CeeDeviceIdentity {
    DeviceId electromagnetism$nativeIdentity();
    Tag electromagnetism$saveIdentity();
    void electromagnetism$restoreIdentity(Tag tag);
    String electromagnetism$identityProblem();
    void electromagnetism$retireTransferredDevice();
}
