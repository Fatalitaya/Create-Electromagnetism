package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.*;
import com.electromagnetism.config.ModConfigs;
import com.george_vi.electroenergetics.devices.device.SimulatedDevice;
import com.george_vi.electroenergetics.foundation.nodes.*;
import com.george_vi.electroenergetics.simulation.infrastructure.*;
import java.util.*;
import static com.electromagnetism.simulation.SimulationProtocol.*;

import static com.electromagnetism.integration.cee.nativebridge.CeeNativeWorld.branchId;

import static com.electromagnetism.integration.cee.nativebridge.NativeModelParameters.*;

final class NativeSwitchAdapter implements NativeDeviceAdapter {
    @Override public boolean capture(NativeCaptureContext context,SimulatedDevice device,DeviceId id) {
        var infrastructure=context.infrastructure();
        var devicesByBranch=context.devicesByBranch();
        var damageAssemblies=context.damageAssemblies();
        var protectiveFuses=context.protectiveFuses();
        var descriptors=context.descriptors();
        var branches=context.branches();
        var ports=context.ports();
        UUID main=branchId(device,"main");
        var p0=new PortId(id,0);var p1=new PortId(id,1);var internal=new PortId(id,2);
        if(device instanceof com.george_vi.electroenergetics.content.transmission_distribution.hv_switch.HVSwitchDevice hv) {
            descriptors.add(new DeviceInput(id,"ports",List.of(p0),Map.of()));ports.put(new InWorldNode(0,device.pos),p0);
            if(hv.target!=null) {
                var target=infrastructure.deviceSD.getDevice(hv.target,com.george_vi.electroenergetics.content.connector.ConnectorDevice.class);
                if(target==null || !target.isHVSwitchTarget)throw new IllegalArgumentException("High voltage switch target missing or invalid");
                var otherId=((CeeDeviceIdentity)target).electromagnetism$nativeIdentity();
                if(!Float.isFinite(hv.progress)||hv.progress<0||hv.progress>1)throw new IllegalArgumentException("Invalid high voltage switch position");
                double gap=Math.max(0.01,(1-hv.progress)*device.pos.getCenter().distanceTo(hv.target.getCenter()));
                branches.add(new Branch(main,p0,new PortId(otherId,0),"air_gap",NativeAirGap.parameters(hv.progress>=0.9,Math.max(1,50000*(1-hv.progress)),gap)));
                devicesByBranch.put(main,device);
            }
            return true;
        } else         if(device instanceof com.george_vi.electroenergetics.content.relay.RelayDevice relay) {
            var terminals=java.util.stream.IntStream.range(0,4).mapToObj(n->new PortId(id,n)).toList();
            var coil=new PortId(id,4);
            var declared=new ArrayList<>(terminals);declared.add(coil);
            descriptors.add(new DeviceInput(id,"ports",declared,Map.of()));
            for(int n=0;n<4;n++)ports.put(new InWorldNode(n,device.pos),terminals.get(n));
            branches.add(new Branch(main,coil,p1,"relay",Map.of("coilOhms",1000.0,"closedOhms",0.001,"openOhms",1e12,"pickupVolts",4.0,"dropoutVolts",3.0,"inverted",relay.inverted?1.0:0.0,"initialActive",0.0),List.of(terminals.get(2),terminals.get(3))));
            branches.add(new Branch(branchId(device,"relay/flux"),p0,coil,"inductor",Map.of("inductanceHenries",0.05)));
            devicesByBranch.put(main,device);return true;
        } else         if(device instanceof com.electromagnetism.content.electrical.switching.ReversingSwitchDevice reversing) {
            var p3=new PortId(id,3);
            descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1,internal,p3),Map.of()));
            boolean reverse=infrastructure.level.getBlockState(device.pos).getValue(com.george_vi.electroenergetics.content.cut_off_switch.CutOffSwitchBlock.CLOSED);
            double resistance=ModConfigs.server().reversingSwitchResistance.getF();
            branches.add(new Branch(branchId(device,"pole/0"),p0,reverse?p3:internal,"resistor",Map.of("resistanceOhms",resistance)));
            branches.add(new Branch(branchId(device,"pole/1"),p1,reverse?internal:p3,"resistor",Map.of("resistanceOhms",resistance)));
            ports.put(new InWorldNode(2,device.pos),internal);ports.put(new InWorldNode(3,device.pos),p3);
        } else         if(device instanceof com.george_vi.electroenergetics.content.transmission_distribution.sf6_breaker.SF6BreakerDevice breaker) {
            descriptors.add(new DeviceInput(id,"ports",List.of(p0),Map.of()));
            ports.put(new InWorldNode(0,device.pos),p0);
            var state=infrastructure.level.getBlockState(device.pos);
            if(!(state.getBlock() instanceof com.george_vi.electroenergetics.content.transmission_distribution.sf6_breaker.SF6BreakerBlock)
                    || state.getValue(com.george_vi.electroenergetics.content.transmission_distribution.sf6_breaker.SF6BreakerBlock.BASE)!=breaker.base)
                throw new IllegalArgumentException("SF6 breaker world/base ownership mismatch at "+device.pos);
            var otherPos=breaker.base?device.pos.above():device.pos.below();
            if(!(infrastructure.deviceSD.getDevice(otherPos) instanceof com.george_vi.electroenergetics.content.transmission_distribution.sf6_breaker.SF6BreakerDevice other) || other.base==breaker.base)
                throw new IllegalArgumentException("SF6 breaker requires its complementary half at "+otherPos);
            var otherState=infrastructure.level.getBlockState(otherPos);
            if(otherState.getBlock()!=state.getBlock()
                    || otherState.getValue(com.george_vi.electroenergetics.content.transmission_distribution.sf6_breaker.SF6BreakerBlock.BASE)!=other.base)
                throw new IllegalArgumentException("SF6 complementary world owner mismatch at "+otherPos);
            damageAssemblies.put(device,List.of(device,other));
            if(breaker.base) {
                boolean closed=!breaker.powered && !other.powered;
                // Redstone supplies contact commands. The native contact model handles current,
                // heating and protective interruption.
                for(int depth=1;depth<=2;depth++) {
                    if(!(infrastructure.deviceSD.getDevice(device.pos.below(depth)) instanceof com.george_vi.electroenergetics.content.connector.InsulatorDevice insulator))break;
                    closed &= !insulator.powered;
                }
                var otherId=((CeeDeviceIdentity)other).electromagnetism$nativeIdentity();
                double rating=ModConfigs.server().nativeSwitchRatedAmperes.getF(),cooling=rating*rating*0.01/100;
                branches.add(new Branch(main,p0,new PortId(otherId,0),"thermal_fuse",thermal(closed?0.01:1e12,cooling,cooling,393.15)));
                devicesByBranch.put(main,device);
            }
            return true;
        } else         if(device instanceof com.george_vi.electroenergetics.content.cut_off_switch.CutOffSwitchDevice contact) {
            int lines=contact.type==com.george_vi.electroenergetics.CEESimulatedDevices.DOUBLE_SWITCH.get()?2:1;
            var terminals=java.util.stream.IntStream.range(0,lines*2).mapToObj(n->new PortId(id,n)).toList();
            descriptors.add(new DeviceInput(id,"ports",terminals,Map.of()));
            for(int n=0;n<terminals.size();n++)ports.put(new InWorldNode(n,device.pos),terminals.get(n));
            for(int line=0;line<lines;line++) {
                UUID branch=line==0?main:branchId(device,"contact/"+line);devicesByBranch.put(branch,device);
                branches.add(new Branch(branch,terminals.get(line),terminals.get(lines+line),"thermal_fuse",switchContact(contact.isClosed)));
            }
        } else         if(device instanceof com.george_vi.electroenergetics.content.cut_off_switch.MomentarySwitchDevice contact) {
            if(contact.closedTicks < -1 || contact.closedTicks > 5)throw new IllegalArgumentException("Momentary switch hold must be -1..5 game ticks at "+device.pos);
            descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1),Map.of()));
            boolean pressed=infrastructure.level.getBlockState(device.pos).getValue(com.george_vi.electroenergetics.content.cut_off_switch.MomentarySwitchBlock.CLOSED);
            branches.add(new Branch(main,p0,p1,"thermal_fuse",switchContact(pressed)));
        } else         if(device instanceof com.george_vi.electroenergetics.content.redstone_relay.RedstoneRelayDevice) {
            var state=infrastructure.level.getBlockState(device.pos);
            if(!com.george_vi.electroenergetics.CEEBlocks.REDSTONE_RELAY.has(state))throw new IllegalArgumentException("Missing redstone relay world state at "+device.pos);
            boolean closed=state.getValue(com.george_vi.electroenergetics.content.redstone_relay.RedstoneRelayBlock.POWERED)^state.getValue(com.george_vi.electroenergetics.content.redstone_relay.RedstoneRelayBlock.INVERTED);
            descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1),Map.of()));
            branches.add(new Branch(main,p0,p1,"thermal_fuse",switchContact(closed)));
        } else         if(device instanceof NativeFuseHolder holder) {
            var terminals=java.util.stream.IntStream.range(0,4).mapToObj(n->new PortId(id,n)).toList();
            descriptors.add(new DeviceInput(id,"ports",terminals,Map.of()));
            for(int n=0;n<4;n++)ports.put(new InWorldNode(n,device.pos),terminals.get(n));
            for(var slot:holder.electromagnetism$slots()) {
                UUID branch=branchId(device,"fuse-holder/"+slot.identity());
                Map<String,Double> parameters=switch(slot.kind()) {
                    case "fuse" -> FuseParameters.forRating(slot.amperes());
                    case "switch" -> switchContact(slot.closed());
                    case "copper" -> thermal(0.1,20,0.2,393.15);
                    case "indicator" -> NativeProtection.indicator();
                    default -> throw new IllegalArgumentException("Unknown fuse holder attachment "+slot.kind());
                };
                if(slot.kind().equals("fuse"))protectiveFuses.add(branch);
                if(!slot.broken()) {
                    branches.add(new Branch(branch,terminals.get(slot.positive()),terminals.get(slot.negative()),"thermal_fuse",parameters));
                    devicesByBranch.put(branch,device);
                }
            }
        } else         if(device instanceof com.george_vi.electroenergetics.content.fuse.FuseDevice fuse) {
            descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1),Map.of()));
            if(!fuse.isBroken)branches.add(new Branch(main,p0,p1,"thermal_fuse",FuseParameters.forRating(fuse.setAmperage)));
        } else {return false;}
        context.ports().put(new InWorldNode(0,device.pos),p0);context.ports().put(new InWorldNode(1,device.pos),p1);
        context.devicesByBranch().put(main,device);
        return true;
    }
}
