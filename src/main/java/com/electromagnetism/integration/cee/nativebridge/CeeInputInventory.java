package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.SimulationProtocol.DeviceInput;
import com.george_vi.electroenergetics.CEESimulatedDeviceFeatureTypes;
import com.george_vi.electroenergetics.devices.device.DevicesSavedData;
import java.util.ArrayList;
import java.util.List;

/** Read-only audit of the exact collection previously broadcast to preTick/postTick.
 * This deliberately cannot masquerade as a complete topology or a supported circuit.
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
        for (var device : devices.getDevices(CEESimulatedDeviceFeatureTypes.TICKING_ELECTRICAL.get())) {
            if (device instanceof CeeDeviceSource source) {
                try { inputs.add(source.electromagnetism$captureInput()); }
                catch (IllegalArgumentException | IllegalStateException invalid) {
                    blockers.add(device.type.id() + " at " + device.pos + ": " + invalid.getMessage());
                }
            } else blockers.add("No native descriptor: " + device.type.id() + " at " + device.pos);
        }
        blockers.add("Native model support, identity transfer during moves and SI conversion contracts are not validated");
        blockers.add("Wire topology, panel attachments, contact/electrocution/lightning branches and third-party listeners require explicit adapters");
        return new CeeInputInventory(inputs, blockers);
    }
}
