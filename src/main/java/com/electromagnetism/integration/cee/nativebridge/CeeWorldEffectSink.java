package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.SimulationProtocol.DeviceId;
import com.electromagnetism.simulation.SimulationProtocol.Revision;
import java.util.UUID;

/** World-thread effects separate from numeric observations. No implementation is installed yet. */
public interface CeeWorldEffectSink {
    /** Must validate incarnation/revision and deduplicate eventId before changing the world.
     * Native must stop at the event boundary until the world confirms the resulting topology.
     */
    void disconnect(Revision revision, UUID eventId, DeviceId device, UUID branchId);
    /** Mechanical conversion must be defined before mapping this SI torque to Create stress. */
    void applyTorque(Revision revision, DeviceId device, double torqueNewtonMetres);
    /** Physical temperature is native-owned; Java only mirrors it to UI/gameplay. */
    void publishTemperature(Revision revision, DeviceId device, double temperatureKelvin);
}
