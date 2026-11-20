package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.config.ModConfigs;
import com.electromagnetism.content.electromechanics.AcMotorBlockEntity;
import com.electromagnetism.registry.ModBlocks;
import com.electromagnetism.simulation.SimulationCoordinator;
import com.electromagnetism.simulation.SimulationProtocol.*;
import com.george_vi.electroenergetics.devices.device.SimulatedDevice;
import com.george_vi.electroenergetics.foundation.nodes.InWorldNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static com.electromagnetism.integration.cee.nativebridge.CeeNativeWorld.branchId;

/** One pole pair, shorted rotor windings, all currents referred to the stationary stator axes. */
final class NativeInductionMotor {
    private static final double RUN_CAPACITANCE_FARADS = 0.0047;
    private static final double COUPLING_FACTOR = 0.9;
    private static final double OPERATING_SLIP = 0.1;
    private final NativeCaptureContext context;
    private final SimulatedDevice device;
    private final DeviceId id;
    private final List<PortId> nodes = new ArrayList<>();

    NativeInductionMotor(NativeCaptureContext context, SimulatedDevice device, DeviceId id) {
        this.context = context;
        this.device = device;
        this.id = id;
    }

    private PortId node() {
        var port = new PortId(id, nodes.size());
        nodes.add(port);
        return port;
    }

    void capture() {
        if (!(context.infrastructure().level.getBlockEntity(device.pos) instanceof AcMotorBlockEntity motor))
            throw new IllegalArgumentException("Missing induction motor shaft at " + device.pos);
        int axes = motor.getBlockState().is(ModBlocks.THREE_PHASE_MOTOR.get()) ? 3 : 2;
        var leads = new PortId[axes];
        for (int i = 0; i < axes; i++) {
            leads[i] = node();
            context.ports().put(new InWorldNode(i, device.pos), leads[i]);
        }
        var common = axes == 3 ? node() : leads[1];
        if (axes == 2) {
            // The auxiliary stator axis is in quadrature and fed through a run capacitor.
            leads[1] = node();
            add("run-capacitor", leads[0], leads[1], "capacitor", Map.of("capacitanceFarads", RUN_CAPACITANCE_FARADS));
        }
        double resistance = ModConfigs.server().nativeMotorWindingOhms.getF();
        double inductance = ModConfigs.server().nativeMotorWindingHenries.getF();
        double mutual = COUPLING_FACTOR * inductance;
        double omega = motor.electromagnetism$shaftSpeedRpm() * Math.PI / 30;
        var stator = new PortId[axes];
        var rotor = new PortId[axes];
        var rotorStart = new PortId[axes];
        for (int i = 0; i < axes; i++) {
            stator[i] = node();
            rotorStart[i] = node();
            rotor[i] = rotorStart[i];
            if (axes == 3 || i == 0) {
                // The phase-to-star probes also define the floating star potential at
                // initialization, when all winding currents are constrained to zero.
                add("supply/" + i, leads[i], common, "resistor", Map.of("resistanceOhms", 1e9));
            }
            add("copper/" + i, leads[i], stator[i], "thermal_fuse",
                    NativeModelParameters.thermal(resistance, 100, 1, 393.15));
        }

        // v_rotor = R i + d(flux)/dt - omega J flux, flux = M i_stator + L i_rotor.
        // Each field_shaft reads a winding current with a series zero-volt sensor.
        // The native solver integrates torque and the matching shaft power together.
        for (int i = 0; i < axes; i++) {
            PortId from = common;
            for (int offset = 1; offset < axes; offset++) {
                int field = (i + offset) % axes;
                double coefficient = axes == 3 ? (offset == 1 ? 1 : -1) / Math.sqrt(3)
                        : (i == 0 ? 1 : -1);
                PortId middle = node();
                sense("mutual/" + i + "/" + field, from, middle, stator, field, coefficient * mutual, omega);
                PortId next = offset == axes - 1 ? rotorStart[i] : node();
                sense("rotor/" + i + "/" + field, middle, next, rotor, field, coefficient * inductance, omega);
                from = next;
            }
        }
        for (int i = 0; i < axes; i++) {
            context.branches().add(new Branch(branchId(device, "windings/" + i), stator[i], common, "coupled_windings",
                    Map.of("primaryHenries", inductance, "secondaryHenries", inductance, "mutualHenries", mutual,
                            "primaryOhms", 0.0, "secondaryOhms", resistance), List.of(rotor[i], common)));
        }
        context.descriptors().add(new DeviceInput(id, "ports", nodes, Map.of()));
    }

    private void add(String role, PortId from, PortId to, String model, Map<String, Double> parameters) {
        var branch = branchId(device, role);
        context.branches().add(new Branch(branch, from, to, model, parameters));
        context.devicesByBranch().put(branch, device);
    }

    private void sense(String role, PortId from, PortId to, PortId[] winding, int field, double coupling, double omega) {
        PortId end = node();
        var branch = branchId(device, role);
        context.branches().add(new Branch(branch, from, to, "field_shaft",
                Map.of("newtonMetresPerAmpereSquared", coupling, "radiansPerSecond", omega), List.of(winding[field], end)));
        winding[field] = end;
        context.devicesByBranch().put(branch, device);
    }

    static void deliver(SimulatedDevice device, AcMotorBlockEntity motor, SimulationCoordinator.Frame live,
                        SimulationCoordinator.Frame historical, Map<UUID, Reading> readings) {
        int axes = motor.getBlockState().is(ModBlocks.THREE_PHASE_MOTOR.get()) ? 3 : 2;
        double torque = 0;
        // The rotor self-flux contributions cancel (i dot J i = 0).
        for (int i = 0; i < axes; i++) {
            for (int offset = 1; offset < axes; offset++) {
                var branch = branchId(device, "mutual/" + i + "/" + ((i + offset) % axes));
                var reading = readings.get(branch);
                if (!SimulationCoordinator.currentMechanicalBranch(live, historical, branch)) return;
                if (reading == null || reading.torqueNewtonMetres().isEmpty()) {
                    motor.stopElectricalDrive();
                    return;
                }
                torque += reading.torqueNewtonMetres().getAsDouble();
            }
        }
        var phase = new double[axes == 3 ? 3 : 1];
        double hz = 0;
        for (int i = 0; i < phase.length; i++) {
            var supply = readings.get(branchId(device, "supply/" + i));
            if (supply == null || supply.rmsVoltage() < 1e-5 || supply.phase().isEmpty() || supply.frequency().isEmpty()) {
                motor.stopElectricalDrive();
                return;
            }
            double frequency = supply.frequency().getAsDouble();
            if (i == 0) hz = frequency;
            if (hz <= 0 || Math.abs(hz - frequency) > 0.05 * hz) {
                motor.stopElectricalDrive();
                return;
            }
            phase[i] = supply.phase().getAsDouble();
        }
        // The single-phase capacitor permanently advances the auxiliary axis.
        double rotation = -1;
        if (axes == 3)
            rotation = Math.sin(phase[0] - phase[1]) + Math.sin(phase[1] - phase[2]) + Math.sin(phase[2] - phase[0]);
        if (Math.abs(rotation) < 0.01) {
            motor.stopElectricalDrive();
            return;
        }
        // Create requests one shaft speed and a stress capacity. Reserve 10% slip at this
        // operating point; the actual shaft speed is fed back to every native speed EMF.
        double limit = com.simibubi.create.infrastructure.config.AllConfigs.server().kinetics.maxRotationSpeed.get();
        double rpm = Math.min(limit, hz * 60 * (1 - OPERATING_SLIP));
        motor.acceptElectricalDrive(Math.copySign(Math.rint(rpm * 16) / 16, rotation), torque);
    }
}
