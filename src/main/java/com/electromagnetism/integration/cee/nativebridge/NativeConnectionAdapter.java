package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.*;
import com.electromagnetism.config.ModConfigs;
import com.george_vi.electroenergetics.devices.device.SimulatedDevice;
import com.george_vi.electroenergetics.foundation.nodes.*;
import com.george_vi.electroenergetics.simulation.infrastructure.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.electromagnetism.simulation.SimulationProtocol.*;

import static com.electromagnetism.integration.cee.nativebridge.CeeNativeWorld.branchId;

import static com.electromagnetism.integration.cee.nativebridge.NativeModelParameters.*;

final class NativeConnectionAdapter implements NativeDeviceAdapter {
    @Override public boolean capture(NativeCaptureContext context,SimulatedDevice device,DeviceId id) {
        var infrastructure=context.infrastructure();
        var panels=context.panels();
        var devicesByBranch=context.devicesByBranch();
        var nodesByPosition=context.nodesByPosition();
        var descriptors=context.descriptors();
        var branches=context.branches();
        var ports=context.ports();
        var identities=context.identities();
        UUID main=branchId(device,"main");
        var p0=new PortId(id,0);var p1=new PortId(id,1);var internal=new PortId(id,2);
        if(device instanceof com.george_vi.electroenergetics.content.electrical_panel.ElectricalPanelDevice panel) {
            panels.capture(panel,id,descriptors,ports,branches);return true;
        } else         if(device instanceof com.george_vi.electroenergetics.content.connector.ConnectorDevice
                || device instanceof com.george_vi.electroenergetics.content.connector.DoubleConnectorDevice
                || device instanceof com.george_vi.electroenergetics.content.connector.InsulatorDevice
                || device instanceof com.george_vi.electroenergetics.content.bundled_wire.BundledWireTerminationDevice
                || device instanceof com.george_vi.electroenergetics.foundation.base.TemporaryDevice) {
            // These are terminal owners, not implicit shorts between distinct node IDs.
            var terminals=nodesByPosition.getOrDefault(device.pos,List.of()).stream()
                    .sorted(Comparator.comparingInt(InWorldNode::id)).toList();
            var declared=new ArrayList<PortId>();
            for(var node:terminals) {var port=new PortId(id,node.id());declared.add(port);ports.put(node,port);}
            descriptors.add(new DeviceInput(id,"ports",declared,Map.of()));
            return true;
        } else         if(device instanceof com.george_vi.electroenergetics.content.railway_electrification.pantograph.PantographDevice
                || device instanceof com.george_vi.electroenergetics.content.railway_electrification.third_rail.RailContactShoeDevice) {
            descriptors.add(new DeviceInput(id,"ports",List.of(p0),Map.of()));ports.put(new InWorldNode(0,device.pos),p0);return true;
        } else         if(device instanceof com.george_vi.electroenergetics.content.pole.ConcretePoleDevice) {
            var terminals=java.util.stream.IntStream.range(0,4).mapToObj(n->new PortId(id,n)).toList();
            descriptors.add(new DeviceInput(id,"ports",terminals,Map.of()));
            var state=infrastructure.level.getBlockState(device.pos);
            if(!(state.getBlock() instanceof com.george_vi.electroenergetics.content.pole.ConcretePoleBlock))throw new IllegalArgumentException("Concrete pole world owner missing at "+device.pos);
            var upper=infrastructure.deviceSD.getDevice(device.pos.above());
            boolean bottom=!(infrastructure.deviceSD.getDevice(device.pos.below()) instanceof com.george_vi.electroenergetics.content.pole.ConcretePoleDevice);
            boolean top=!(upper instanceof com.george_vi.electroenergetics.content.pole.ConcretePoleDevice);
            var config=ModConfigs.server();
            var conductor=thermal(config.nativePoleConductorOhmsPerMetre.getF(),config.nativeWireHeatCapacityJoulesPerKelvinMetre.getF(),config.nativeWireCoolingWattsPerKelvinMetre.getF(),config.nativeWireTripKelvin.getF());
            if(bottom || top)for(int pin=0;pin<4;pin++) {
                ports.put(new InWorldNode(pin,device.pos),terminals.get(pin));
                UUID edge=branchId(device,"terminal-ring/"+pin);devicesByBranch.put(edge,device);
                branches.add(new Branch(edge,terminals.get(pin),terminals.get((pin+1)%4),"thermal_fuse",conductor));
            }
            if(!top) {
                var upperId=((CeeDeviceIdentity)upper).electromagnetism$nativeIdentity();
                UUID edge=branchId(device,"pole-segment/"+upperId.instance()+"/"+upperId.generation());devicesByBranch.put(edge,device);
                branches.add(new Branch(edge,p0,new PortId(upperId,0),"thermal_fuse",conductor));
            }
        } else         if(device instanceof com.george_vi.electroenergetics.content.ground_rod.GroundRodDevice) {
            // One conducting earth node per dimension; finite 1 S rod contact.
            var earthId=new DeviceId(UUID.nameUUIDFromBytes(("electromagnetism/earth/"+infrastructure.level.dimension().location()).getBytes(StandardCharsets.UTF_8)),0);
            var earth=new PortId(earthId,0);
            if(identities.add(earthId))descriptors.add(new DeviceInput(earthId,"ports",List.of(earth),Map.of()));
            descriptors.add(new DeviceInput(id,"ports",List.of(p0),Map.of()));
            branches.add(new Branch(main,p0,earth,"resistor",Map.of("resistanceOhms",1.0)));
            ports.put(new InWorldNode(0,device.pos),p0);devicesByBranch.put(main,device);return true;
        } else {return false;}
        context.ports().put(new InWorldNode(0,device.pos),p0);context.ports().put(new InWorldNode(1,device.pos),p1);
        context.devicesByBranch().put(main,device);
        return true;
    }
}
