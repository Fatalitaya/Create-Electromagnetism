package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.*;
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
            // Actual CEE node labels: 0 cathode, 1 anode. Fixed SI junction parameters.
            branches.add(new Branch(main,p1,p0,"diode",Map.of("saturationAmperes",1e-9,"thermalVolts",0.05,"junctionFarads",1e-11)));
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
