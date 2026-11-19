package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.*;
import com.electromagnetism.config.ModConfigs;
import com.electromagnetism.registry.ModDeviceTypes;
import com.george_vi.electroenergetics.config.CEEConfigs;
import com.george_vi.electroenergetics.devices.device.SimulatedDevice;
import com.george_vi.electroenergetics.foundation.nodes.*;
import com.george_vi.electroenergetics.simulation.infrastructure.*;
import java.util.*;
import static com.electromagnetism.simulation.SimulationProtocol.*;

import static com.electromagnetism.integration.cee.nativebridge.CeeNativeWorld.branchId;

import static com.electromagnetism.integration.cee.nativebridge.NativeModelParameters.*;

final class NativeElectronicAdapter implements NativeDeviceAdapter {
    @Override public boolean capture(NativeCaptureContext context,SimulatedDevice device,DeviceId id) {
        var devicesByBranch=context.devicesByBranch();
        var descriptors=context.descriptors();
        var branches=context.branches();
        var ports=context.ports();
        UUID main=branchId(device,"main");
        var p0=new PortId(id,0);var p1=new PortId(id,1);var internal=new PortId(id,2);
        if(device instanceof com.george_vi.electroenergetics.content.electronic_components.diode.DiodeDevice) {
            descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1),Map.of()));
            // Retain the original junction charge, polarity and branch identity on upgrade.
            branches.add(new Branch(main, p1, p0, "capacitor", Map.of("capacitanceFarads", 1e-11)));
            boolean highVoltage = device.type == ModDeviceTypes.HIGH_VOLTAGE_RECTIFIER.get();
            boolean zener = device.type == ModDeviceTypes.ZENER_DIODE.get();
            double forwardAmperes = highVoltage ? 10.0 : 5.0;
            double ratedWatts = zener ? 5.0 : forwardAmperes * 0.05 * Math.log1p(forwardAmperes / 1e-9);
            double kneeVolts = zener ? 56.0 - 0.02 * 2.0 : (highVoltage ? 4800.0 : 120.0);
            boolean damage = device.type.id().getNamespace().equals("electroenergetics")
                    ? CEEConfigs.server().componentDamage.get() : ModConfigs.server().componentDamage.get();
            UUID junction = branchId(device, "junction");
            branches.add(new Branch(junction, p1, p0, "rated_rectifier", Map.of(
                    "breakdownVolts", kneeVolts, "dynamicOhms", zener ? 2.0 : 1.0,
                    "ratedWatts", ratedWatts, "damageEnabled", damage ? 1.0 : 0.0)));
            devicesByBranch.put(junction, device);
        } else         if(device instanceof com.electromagnetism.content.electronics.bjt.TransistorDevice transistor) {
            var lead=new PortId(id,3);
            descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1,internal,lead),Map.of()));
            branches.add(new Branch(main,lead,internal,transistor.pnp?"pnp_bipolar":"npn_bipolar",
                    SemiconductorParameters.bipolar(transistor.beta,transistor.vbeOn),List.of(p0)));
            UUID protection=branchId(device,"output_protection");
            branches.add(new Branch(protection,p1,lead,"thermal_fuse",SemiconductorParameters.outputProtection(transistor.icMax)));
            devicesByBranch.put(protection,device);ports.put(new InWorldNode(2,device.pos),internal);
        } else         if(device instanceof com.electromagnetism.content.electronics.mosfet.MosfetDevice mosfet) {
            var lead=new PortId(id,3);
            descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1,internal,lead),Map.of()));
            branches.add(new Branch(main,lead,internal,mosfet.pChannel?"p_mosfet":"n_mosfet",
                    SemiconductorParameters.mosfet(mosfet.vth,mosfet.rdsOn),List.of(p0)));
            UUID protection=branchId(device,"output_protection");
            branches.add(new Branch(protection,p1,lead,"thermal_fuse",SemiconductorParameters.outputProtection(mosfet.idMax)));
            devicesByBranch.put(protection,device);ports.put(new InWorldNode(2,device.pos),internal);
        } else {return false;}
        context.ports().put(new InWorldNode(0,device.pos),p0);context.ports().put(new InWorldNode(1,device.pos),p1);
        context.devicesByBranch().put(main,device);
        return true;
    }
}
