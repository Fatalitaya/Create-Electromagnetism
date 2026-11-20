package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.SimulationProtocol.DeviceId;
import net.minecraft.nbt.Tag;

/** Persist the instance identity across reads of device parameters. */
public interface CeeDeviceIdentity {
    DeviceId electromagnetism$nativeIdentity();
    Tag electromagnetism$saveIdentity();
    void electromagnetism$restoreIdentity(Tag tag);
    String electromagnetism$identityProblem();
    void electromagnetism$retireTransferredDevice();
}
