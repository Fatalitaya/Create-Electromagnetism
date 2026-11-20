package com.electromagnetism.integration.cee.nativebridge;

import com.crystaelix.simurail.content.automatic_coupler.AutomaticCouplerBlockEntity;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import com.electromagnetism.simulation.SimulationProtocol.*;
import com.george_vi.electroenergetics.devices.device.SimulatedDevice;
import com.george_vi.electroenergetics.foundation.nodes.InWorldNode;
import com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData;
import java.util.ArrayList;
import java.util.Map;

/** Mirror SimuRail's two CEE devices at the native capture boundary. */
final class SimurailContacts {
    static boolean capture(NativeCaptureContext context, SimulatedDevice device, DeviceId id) {
        var sd = context.infrastructure();
        var be = sd.level.getBlockEntity(device.pos);
        boolean bogey = device.type.id().getPath().equals("physics_bogey");
        if (!bogey && !device.type.id().getPath().equals("automatic_coupler")) return false;
        if (bogey && !(be instanceof PhysicsBogeyBlockEntity)
                || !bogey && !(be instanceof AutomaticCouplerBlockEntity))
            throw new IllegalArgumentException("Missing SimuRail contact owner at " + device.pos);
        var terminals = new ArrayList<PortId>();
        for (int pin = 0; pin < (bogey ? 3 : 1); pin++) {
            var port = new PortId(id, pin);
            terminals.add(port);
            context.ports().put(new InWorldNode(pin, device.pos), port);
        }
        if (bogey) {
            // The studs bond to the wheel frame. Each axle then contacts its actual rail edge.
            var frame = new PortId(id, 3);
            for (var port : terminals)
                context.branches().add(new Branch(CeeNativeWorld.branchId(device, "wheel/" + port.terminal()),
                        port, frame, "resistor", Map.of("resistanceOhms", 1.0)));
            terminals.add(frame);
        }
        context.descriptors().add(new DeviceInput(id, "ports", terminals, Map.of()));
        if (be instanceof AutomaticCouplerBlockEntity coupler && coupler.isPrimary()) {
            var pos = coupler.getPartner();
            var peer = sd.deviceSD.getDevice(pos);
            if (!sd.level.isLoaded(pos) || !(sd.level.getBlockEntity(pos) instanceof AutomaticCouplerBlockEntity other)
                    || !device.pos.equals(other.getPartner()) || peer == null || peer.type != device.type
                    || !(peer instanceof CeeDeviceIdentity owner))
                throw new IllegalArgumentException("Unresolved SimuRail coupler partner at " + pos);
            context.branches().add(new Branch(CeeNativeWorld.branchId(device, "coupler"), terminals.getFirst(),
                    new PortId(owner.electromagnetism$nativeIdentity(), 0), "resistor", Map.of("resistanceOhms", 0.01)));
        }
        return true;
    }

    static void captureWheels(NativeRunningRails rails, InfrastructureSavedData sd, SimulatedDevice device, PortId frame) {
        if (!(sd.level.getBlockEntity(device.pos) instanceof PhysicsBogeyBlockEntity bogey) || bogey.isDerailed()) return;
        for (boolean front : new boolean[]{true, false}) {
            var axle = bogey.getAxle(front);
            if (!axle.hasTrack()) continue;
            var point = axle.getTrackPoint();
            var graph = axle.getTrackGraph();
            if (graph != null && point != null)
                rails.axle(graph, point.edge, point.position, "simurail/" + frame.device().instance()
                        + (front ? "/front" : "/back"), frame);
        }
    }
}
