package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.SimulationProtocol.DeviceInput;

/** World-thread input collection only. Must not call preTick, stamp, integrate, or consume energy. */
public interface CeeDeviceSource {
    DeviceInput electromagnetism$captureInput();
}
