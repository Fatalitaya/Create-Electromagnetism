package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.*;
import com.electromagnetism.config.ModConfigs;
import com.george_vi.electroenergetics.devices.device.SimulatedDevice;
import com.george_vi.electroenergetics.foundation.nodes.*;
import com.george_vi.electroenergetics.simulation.infrastructure.*;
import java.util.*;
import static com.electromagnetism.simulation.SimulationProtocol.*;

import static com.electromagnetism.integration.cee.nativebridge.CeeNativeWorld.branchId;

final class NativeModelParameters {
    private NativeModelParameters() {}
    static void storageParasitics(NativeCaptureContext context,SimulatedDevice device,PortId positive,PortId negative,PortId internal,double ratedVolts,List<Branch> branches) {
        if(!Double.isFinite(ratedVolts) || ratedVolts<=0)throw new IllegalArgumentException("Positive finite insulation voltage rating required");
        UUID loss=branchId(device,"series_loss"),insulation=branchId(device,"insulation");
        branches.add(new Branch(loss,internal,negative,"thermal_fuse",thermal(0.1,100,1,673.15)));
        double conductance=ratedVolts*ratedVolts/1e11;
        branches.add(new Branch(insulation,positive,internal,"thermal_fuse",thermal(1e9,conductance,conductance,393.15)));
        context.devicesByBranch().put(loss,device);context.devicesByBranch().put(insulation,device);
    }
    /** New SI thermal parameters; no conversion of CEE's empirical temperature fields. */
    /** Instantaneous commanded contacts with finite insulation and thermal damage.
     * Valid as a lumped switch without an arc plasma model. Off-state insulation
     * is a physical 1 Tohm path, not a numerical shunt. The same thermal node is
     * retained across command changes; no legacy SwitchingBehaviour is advanced.
     */
    static Map<String,Double> switchContact(boolean closed) {
        double rating=ModConfigs.server().nativeSwitchRatedAmperes.getF();
        double cooling=rating*rating*0.001/100;
        return thermal(closed?0.001:1e12,cooling,cooling,393.15);
    }
    static Map<String,Double> thermal(double resistance,double capacity,double conductance,double trip) {
        return NativeProtection.thermal(resistance,capacity,conductance,trip);
    }
}
