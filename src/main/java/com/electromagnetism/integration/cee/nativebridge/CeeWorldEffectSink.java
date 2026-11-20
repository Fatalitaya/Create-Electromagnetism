package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.SimulationProtocol.DeviceId;
import com.electromagnetism.simulation.SimulationProtocol.Revision;
import java.util.UUID;

/** Deliver world effects on the owning thread. Numerical observations can be read independently. */
public interface CeeWorldEffectSink {
    /**
     * Validate instance, revision and event identity before changing the world. The native session
     * waits at the event boundary for topology confirmation.
     */
    void disconnect(Revision revision, UUID eventId, DeviceId device, UUID branchId);
    /** Convert SI torque through the mechanical adapter before updating Create stress. */
    void applyTorque(Revision revision, DeviceId device, double torqueNewtonMetres);
    /** Rust advances temperature. Java uses accepted readings for display and gameplay. */
    void publishTemperature(Revision revision, DeviceId device, double temperatureKelvin);
}
