package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.SimulationProtocol.Branch;
import com.george_vi.electroenergetics.config.CEEConfigs;
import java.util.HashMap;
import java.util.Map;

/** Damage permission is conveyed to Rust, before it evaluates protection. */
public final class NativeProtection {
    private NativeProtection() {}

    public static Branch destructive(Branch branch, boolean enabled) {
        if (enabled) return branch;
        if (branch.model().equals("tapped_wire")) {
            // The existing trip slot uses zero for no destructive threshold; Rust
            // still retains the distributed conductor's single thermal body.
            var values = new HashMap<>(branch.parameters());
            values.put("tripKelvin", 0.0);
            return new Branch(branch.id(), branch.positive(), branch.negative(), branch.model(), values, branch.additionalPorts());
        }
        if (!branch.model().equals("thermal_fuse")) return branch;
        var parameters = new HashMap<>(branch.parameters());
        parameters.remove("tripKelvin");
        return new Branch(branch.id(), branch.positive(), branch.negative(), "thermal_resistor",
                parameters, branch.additionalPorts());
    }

    public static Map<String, Double> thermal(double ohms, double capacity, double cooling, double tripKelvin) {
        return Map.of("ambientOhms", ohms, "coefficientPerKelvin", 0.0,
                "heatCapacityJoulesPerKelvin", capacity, "conductanceWattsPerKelvin", cooling,
                "ambientKelvin", 293.15, "tripKelvin", tripKelvin);
    }

    public static Map<String, Double> gauge(boolean voltmeter) {
        double ohms = voltmeter ? 1e6 : 0.01;
        double rating = voltmeter ? CEEConfigs.server().voltageValues.maxVoltmeterVoltage.get()
                : CEEConfigs.server().voltageValues.maxAmmeterCurrent.get();
        double lossWatts = voltmeter ? rating * rating / ohms : rating * rating * ohms;
        return thermal(ohms, lossWatts / 100, lossWatts / 100, 393.15);
    }

    public static Map<String, Double> indicator() {
        double ohms = CEEConfigs.server().resistanceValues.indicatorBulbResistance.get();
        return thermal(ohms, 0.1, 70 * 70 / ohms / 100, 493.15);
    }
}
