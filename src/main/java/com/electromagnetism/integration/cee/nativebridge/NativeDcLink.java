package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.SimulationProtocol.Branch;
import com.electromagnetism.simulation.SimulationProtocol.PortId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Captured rectifier rails. Each owner supplies the identities of its junctions and storage. */
record NativeDcLink(PortId positive, PortId negative) {
    private static final Map<String, Double> DIODE = Map.of(
            "saturationAmperes", 1e-9, "thermalVolts", 0.05, "junctionFarads", 0.0);

    void addBuffer(List<Branch> branches, PortId stored, UUID charge, UUID gate,
                   double farads, double contactOhms, double fullVolts, double taperVolts) {
        branches.add(new Branch(charge, stored, negative, "capacitor", Map.of("capacitanceFarads", farads)));
        branches.add(new Branch(gate, positive, stored, "battery_contact", Map.of(
                "resistanceOhms", contactOhms, "fullVolts", fullVolts, "taperVolts", taperVolts), List.of(negative)));
    }

    void addBridge(List<Branch> branches, PortId a, PortId b, UUID highA, UUID highB, UUID lowA, UUID lowB) {
        branches.add(high(highA, a));
        branches.add(high(highB, b));
        branches.add(low(lowA, a));
        branches.add(low(lowB, b));
    }

    void addLeg(List<Branch> branches, PortId ac, UUID high, UUID low) {
        branches.add(high(high, ac));
        branches.add(low(low, ac));
    }

    private Branch high(UUID id, PortId ac) {
        return new Branch(id, ac, positive, "diode", DIODE);
    }

    private Branch low(UUID id, PortId ac) {
        return new Branch(id, negative, ac, "diode", DIODE);
    }
}
