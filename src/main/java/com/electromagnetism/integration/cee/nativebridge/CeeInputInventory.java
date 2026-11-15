package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.SimulationProtocol.DeviceInput;
import com.george_vi.electroenergetics.devices.device.DevicesSavedData;
import java.util.ArrayList;
import java.util.List;

/** Capture results used by runtime diagnostics. The DevicesSavedData overload only
 * audits identity integrity; actual coverage must use CeeSimulationAccess.inspectInputs.
 */
public record CeeInputInventory(List<DeviceInput> inputs, List<String> blockers) {
    public CeeInputInventory { inputs = List.copyOf(inputs); blockers = List.copyOf(blockers); }
    public static CeeInputInventory capture(DevicesSavedData devices) {
        var inputs = new ArrayList<DeviceInput>();
        var blockers = new ArrayList<String>();
        var identities = new java.util.HashSet<com.electromagnetism.simulation.SimulationProtocol.DeviceId>();
        for (var device : devices.getDevices()) {
            if (device instanceof CeeDeviceIdentity identity) {
                if (!identity.electromagnetism$identityProblem().isEmpty())
                    blockers.add(device.pos + ": " + identity.electromagnetism$identityProblem());
                if (!identities.add(identity.electromagnetism$nativeIdentity()))
                    blockers.add("Duplicate native device identity at " + device.pos);
            } else blockers.add("Missing native identity at " + device.pos);
        }
        return new CeeInputInventory(inputs, blockers);
    }
}
