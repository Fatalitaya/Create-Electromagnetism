package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.*;
import com.george_vi.electroenergetics.devices.device.SimulatedDevice;
import com.george_vi.electroenergetics.foundation.nodes.*;
import com.george_vi.electroenergetics.simulation.infrastructure.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.electromagnetism.simulation.SimulationProtocol.*;

import static com.electromagnetism.integration.cee.nativebridge.CeeNativeWorld.branchId;

final class NativeWorldCapture {
    private final SimulationFaultLog faults=new SimulationFaultLog();
    private final Map<DeviceId,SimulationFaultLog> deviceFaults=new HashMap<>();
    private final Map<UUID,SimulatedDevice> devicesByBranch=new HashMap<>();
    private final Map<UUID,InWorldNodeConnection> wiresByBranch=new HashMap<>();
    private final Map<SimulatedDevice,List<SimulatedDevice>> damageAssemblies=new IdentityHashMap<>();
    private final Set<UUID> protectiveFuses=new HashSet<>();
    private Set<UUID> catenaryBranches=Set.of();
    private long captureTick=Long.MIN_VALUE;
    private double captureTime;
    private Map<InWorldNode,PortId> nodePorts=Map.of();
    private final InfrastructureSavedData infrastructure;
    private final NativePanels panels;
    private final NativeAlternators alternators;
    private final NativeRadioLinks radioLinks;
    private final NativeEnvironment environment;
    private final NativeTrains trains;
    private final java.util.function.DoubleSupplier targetTime;
    private final java.util.function.Supplier<SimulationCoordinator.Frame> previous;
    NativeWorldCapture(InfrastructureSavedData infrastructure,NativePanels panels,NativeAlternators alternators,NativeRadioLinks radioLinks,NativeEnvironment environment,NativeTrains trains,java.util.function.DoubleSupplier targetTime,java.util.function.Supplier<SimulationCoordinator.Frame> previous) {
        this.infrastructure=infrastructure;
        this.panels=panels;
        this.alternators=alternators;
        this.radioLinks=radioLinks;
        this.environment=environment;
        this.trains=trains;
        this.targetTime=targetTime;
        this.previous=previous;
    }
    Map<UUID,InWorldNodeConnection> wires(){return wiresByBranch;}
    Map<InWorldNode,PortId> nodePorts(){return nodePorts;}
    double inputTime(){return captureTime;}
    @jdk.jfr.Name("electromagnetism.WorldCapture")
    @jdk.jfr.Label("Electrical world input capture")
    static final class CaptureTiming extends jdk.jfr.Event {
        long tick;
        @jdk.jfr.Timespan long devices;
        @jdk.jfr.Timespan long alternators;
        @jdk.jfr.Timespan long radio;
        @jdk.jfr.Timespan long wires;
    }

    public SimulationCoordinator.Frame capture() {
        var event=new CaptureTiming();
        event.tick=infrastructure.level.getGameTime();
        event.begin();
        try{var frame=captureChecked(event);if(frame.blockers().isEmpty())alternators.completeCapture();faults.recovered();return frame;}
        catch(IllegalArgumentException | IllegalStateException invalid) {
            faults.report("Capturing electrical world "+infrastructure.level.dimension().location(),new Diagnostic(Status.REJECTED,invalid.getMessage()),invalid);
            nodePorts=Map.of();
            return new SimulationCoordinator.Frame(List.of(),List.of(),List.of("Invalid world input: "+invalid.getMessage()),RemovalDisposition.REJECT_ENERGIZED);
        } finally {event.commit();}
    }
    private SimulationCoordinator.Frame captureChecked(CaptureTiming event) {
        boolean profile=event.isEnabled();
        long phase=profile?System.nanoTime():0L;
        if(captureTick!=infrastructure.level.getGameTime()){captureTick=infrastructure.level.getGameTime();captureTime=targetTime.getAsDouble();}
        if(!infrastructure.deviceSD.devicesToAddSable.isEmpty())return new SimulationCoordinator.Frame(List.of(),List.of(),List.of("Pending Sable ownership handoff: "+((NativeSableTransfers)infrastructure.deviceSD).electromagnetism$transferProblem()),RemovalDisposition.REJECT_ENERGIZED);
        var descriptors=new ArrayList<DeviceInput>();var branches=new ArrayList<Branch>();var blockers=new ArrayList<String>();
        var ownedBlockers=new HashMap<DeviceId,List<String>>();
        var registeredDevices=infrastructure.deviceSD.getDevices();
        panels.begin();
        alternators.begin(infrastructure,registeredDevices);
        var waveforms=new ArrayList<WaveformChannel>();
        var ports=new HashMap<InWorldNode,PortId>();var identities=new HashSet<DeviceId>();
        var nodesByPosition=infrastructure.getNodes().stream().collect(java.util.stream.Collectors.groupingBy(InWorldNode::sourcePos));
        devicesByBranch.clear();wiresByBranch.clear();damageAssemblies.clear();protectiveFuses.clear();
        var deviceCapture=new NativeDeviceCapture(new NativeCaptureContext(infrastructure,panels,alternators,devicesByBranch,damageAssemblies,protectiveFuses,captureTime,nodesByPosition,descriptors,branches,waveforms,ports,identities));
        for(SimulatedDevice device:registeredDevices) {
            if(!(device instanceof CeeDeviceIdentity identity)) {
                // Address only this blocked capture. This key never owns a native session state or effect.
                var key=UUID.nameUUIDFromBytes(("unidentified-port/"+device.pos.asLong()).getBytes(StandardCharsets.UTF_8));
                var placeholder=new DeviceId(key,0);
                ownedBlockers.put(placeholder,List.of("Missing identity: "+device.type.id()+" at "+device.pos));
                for(var node:nodesByPosition.getOrDefault(device.pos,List.of()))
                    ports.put(node,new PortId(placeholder,node.id()));
                continue;
            }
            var id=identity.electromagnetism$nativeIdentity();
            if(!identity.electromagnetism$identityProblem().isEmpty() || !identities.add(id)) {
                ownedBlockers.put(id,List.of("Invalid/duplicate identity: "+device.pos+" "+identity.electromagnetism$identityProblem()));
                for(var node:nodesByPosition.getOrDefault(device.pos,List.of()))ports.put(node,new PortId(id,node.id()));
                continue;
            }
            if(!infrastructure.level.getChunkSource().hasChunk(device.pos.getX()>>4,device.pos.getZ()>>4)) {
                ownedBlockers.put(id,List.of("Unloaded physical owner: "+device.type.id()+" at "+device.pos));retainPorts(id,device,descriptors,ports);continue;
            }
            try {
                deviceCapture.capture(device,id);
                deviceFaults.remove(id);
            } catch(IllegalArgumentException | IllegalStateException invalid) {
                deviceFaults.computeIfAbsent(id,key->new SimulationFaultLog()).report(
                        "Capturing "+device.type.id()+" at "+device.pos+" in "+infrastructure.level.dimension().location(),
                        new Diagnostic(Status.REJECTED,invalid.getMessage()),invalid);
                ownedBlockers.put(id,List.of("Invalid device input at "+device.pos+": "+invalid.getMessage()));
                retainPorts(id,device,descriptors,ports);
            }
        }
        // Adjacent factory segments share both supply rails through explicit finite contacts.
        deviceFaults.keySet().retainAll(identities);
        for(var device:registeredDevices) {
            if(!(device instanceof com.electromagnetism.content.lighting.factory.FactoryLightDevice))continue;
            var state=infrastructure.level.getBlockState(device.pos);
            if(!(state.getBlock() instanceof com.electromagnetism.content.lighting.factory.FactoryLightBlock))continue;
            var axis=state.getValue(com.electromagnetism.content.lighting.factory.FactoryLightBlock.HORIZONTAL_AXIS);
            var neighbour=infrastructure.deviceSD.getDevice(device.pos.relative(net.minecraft.core.Direction.fromAxisAndDirection(axis,net.minecraft.core.Direction.AxisDirection.POSITIVE)));
            if(!(neighbour instanceof com.electromagnetism.content.lighting.factory.FactoryLightDevice))continue;
            var other=infrastructure.level.getBlockState(neighbour.pos);
            if(!(other.getBlock() instanceof com.electromagnetism.content.lighting.factory.FactoryLightBlock)
                    || other.getValue(com.electromagnetism.content.lighting.factory.FactoryLightBlock.HORIZONTAL_AXIS)!=axis)continue;
            for(int terminal=0;terminal<2;terminal++) {
                var a=ports.get(new InWorldNode(terminal,device.pos));var b=ports.get(new InWorldNode(terminal,neighbour.pos));
                if(a==null || b==null) {
                    var reason=List.of("Factory rail owner unloaded: "+device.pos);
                    if(device instanceof CeeDeviceIdentity id)ownedBlockers.put(id.electromagnetism$nativeIdentity(),reason);
                    if(neighbour instanceof CeeDeviceIdentity id)ownedBlockers.put(id.electromagnetism$nativeIdentity(),reason);
                    continue;
                }
                UUID one=a.device().instance(),two=b.device().instance();
                String key=one.compareTo(two)<0?one+"/"+two:two+"/"+one;
                UUID link=UUID.nameUUIDFromBytes(("factory-rail/"+key+"/"+terminal).getBytes(StandardCharsets.UTF_8));
                branches.add(new Branch(link,a,b,"resistor",Map.of("resistanceOhms",0.001)));
            }
        }
        if(profile){long now=System.nanoTime();event.devices=now-phase;phase=now;}
        alternators.finishFields(registeredDevices,branches);
        if(profile){long now=System.nanoTime();event.alternators=now-phase;phase=now;}
        radioLinks.capture(infrastructure,descriptors,branches,waveforms,identities,captureTime);
        if(profile){long now=System.nanoTime();event.radio=now-phase;phase=now;}
        environment.prepareContacts(infrastructure);
        var stableTaps=environment.stableContactIds();
        stableTaps.putAll(trains.prepare(infrastructure));
        for(var device:registeredDevices) {
            if(device instanceof CeeDeviceIdentity owner && infrastructure.level.getBlockEntity(device.pos) instanceof com.electromagnetism.integration.cee.mixin.NativeContactAccess contact && contact.electromagnetism$attachedNode()!=null)
                stableTaps.put(contact.electromagnetism$attachedNode(),owner.electromagnetism$nativeIdentity().instance());
        }
        var crosses=new IdentityHashMap<WireSimulationState.WireCutHandle,List<UUID>>();
        for(var entry:infrastructure.wireSimulationState.getAllConnections())for(var cut:entry.getValue().cuts)if(cut.handle().name.equals("CrossContact"))crosses.computeIfAbsent(cut.handle(),key->new ArrayList<>()).add(((CeeWireIdentity)entry.getValue().wireData).electromagnetism$wireIdentity());
        for(var entry:infrastructure.wireSimulationState.getAllConnections())for(var cut:entry.getValue().cuts)if(cut.handle().name.equals("CrossContact")) {
            var members=crosses.get(cut.handle());members.sort(UUID::compareTo);String key=members.toString();
            stableTaps.put(cut.node(),UUID.nameUUIDFromBytes(("cross-contact/"+key).getBytes(StandardCharsets.UTF_8)));
        }
        var conductors=NativeConductors.capture(infrastructure,ports,identities,descriptors,branches,wiresByBranch,stableTaps,ownedBlockers);
        if(profile)event.wires=System.nanoTime()-phase;
        trains.capture(infrastructure,conductors,ports,descriptors,branches,identities);
        catenaryBranches=conductors.catenary();
        for(var contact:registeredDevices) {
            if(!(contact instanceof CeeDeviceIdentity identity))continue;
            com.george_vi.electroenergetics.foundation.nodes.Node attached=null;
            var be=infrastructure.level.getBlockEntity(contact.pos);
            if(contact instanceof com.george_vi.electroenergetics.content.railway_electrification.pantograph.PantographDevice
                    && be instanceof com.george_vi.electroenergetics.content.railway_electrification.pantograph.PantographBlockEntity pantograph)attached=((com.electromagnetism.integration.cee.mixin.NativeContactAccess)pantograph).electromagnetism$attachedNode();
            else if(contact instanceof com.george_vi.electroenergetics.content.railway_electrification.third_rail.RailContactShoeDevice
                    && be instanceof com.george_vi.electroenergetics.content.railway_electrification.third_rail.RailContactShoeBlockEntity shoe)attached=((com.electromagnetism.integration.cee.mixin.NativeContactAccess)shoe).electromagnetism$attachedNode();
            if(attached==null)continue;
            var target=attached instanceof InWorldNode node?ports.get(node):conductors.taps().get(attached);
            var origin=ports.get(new InWorldNode(0,contact.pos));
            if(target==null || origin==null) {
                var reason=List.of("Unresolved current-collection contact at "+contact.pos);
                ownedBlockers.put(identity.electromagnetism$nativeIdentity(),reason);
                if(target!=null)ownedBlockers.put(target.device(),reason);
                continue;
            }
            UUID branch=branchId(contact,"main");branches.add(new Branch(branch,origin,target,"thermal_fuse",thermal(0.01,1,0.1,393.15)));devicesByBranch.put(branch,contact);
        }
        nodePorts=Map.copyOf(ports);
        environment.capture(infrastructure,ports,identities,descriptors,branches,captureTime);
        environment.captureWireBodies(infrastructure,conductors,descriptors,branches);
        var physicalOwners=new HashMap<DeviceId,SimulatedDevice>();
        for(var device:registeredDevices)
            if(device instanceof CeeDeviceIdentity identity)physicalOwners.put(identity.electromagnetism$nativeIdentity(),device);
        for(var branch:branches)if(branch.model().equals("thermal_fuse") && !panels.owns(branch.id()) && !wiresByBranch.containsKey(branch.id())) {
            var owner=devicesByBranch.get(branch.id());
            if(owner==null)owner=physicalOwners.get(branch.positive().device());
            if(owner==null)ownedBlockers.put(branch.positive().device(),List.of("Missing protection consumer: "+branch.id()));
            else devicesByBranch.putIfAbsent(branch.id(),owner);
        }
        // Resolve each destructive branch through its actual consumer, never its terminal position.
        // A fuse remains protective even when destructive component damage is disabled.
        branches.replaceAll(branch->{
            if(wiresByBranch.containsKey(branch.id()))
                return NativeProtection.destructive(branch,com.george_vi.electroenergetics.config.CEEConfigs.server().wiresBreak.get());
            var owner=devicesByBranch.get(branch.id());
            if (owner instanceof com.electromagnetism.content.electronics.PowerChokeDevice)
                return NativeProtection.destructive(branch, com.electromagnetism.config.ModConfigs.server().componentDamage.get());
            if(owner!=null && !protectiveFuses.contains(branch.id()) && owner.type.id().getNamespace().equals("electroenergetics")
                    && !(owner instanceof com.george_vi.electroenergetics.content.fuse.FuseDevice))
                return NativeProtection.destructive(branch,com.george_vi.electroenergetics.config.CEEConfigs.server().componentDamage.get());
            return branch;
        });
        descriptors.sort(Comparator.comparing(d->d.id().instance()));branches.sort(Comparator.comparing(Branch::id));Collections.sort(blockers);
        waveforms.sort(Comparator.comparing(WaveformChannel::channel));
        var positions=new HashMap<DeviceId,Long>();var recipients=new HashMap<UUID,DeviceId>();
        var networks=new HashMap<DeviceId,Long>();var groups=new HashMap<DeviceId,List<DeviceId>>();
        physicalOwners.forEach((id,device)->{
            positions.put(id,device.pos.asLong());
            if(infrastructure.level.getBlockEntity(device.pos) instanceof com.simibubi.create.content.kinetics.base.KineticBlockEntity kinetic && kinetic.hasNetwork())
                networks.put(id,kinetic.getOrCreateNetwork().id);
        });
        devicesByBranch.forEach((branch,device)->recipients.put(branch,((CeeDeviceIdentity)device).electromagnetism$nativeIdentity()));
        damageAssemblies.forEach((device,members)->groups.put(((CeeDeviceIdentity)device).electromagnetism$nativeIdentity(),members.stream().map(d->((CeeDeviceIdentity)d).electromagnetism$nativeIdentity()).toList()));
        for(var branch:branches)if(panels.owns(branch.id()) && physicalOwners.containsKey(branch.positive().device()))recipients.putIfAbsent(branch.id(),branch.positive().device());
        var wires=new HashMap<UUID,WorldRecipients.Wire>();
        wiresByBranch.forEach((id,wire)->wires.put(id,new WorldRecipients.Wire(wire.node1().sourcePos().asLong(),wire.node1().id(),wire.node2().sourcePos().asLong(),wire.node2().id(),catenaryBranches.contains(id))));
        alternators.locate(positions,networks);
        return new SimulationCoordinator.Frame(descriptors,branches,blockers,RemovalDisposition.EXPORT_STORED_ENERGY,waveforms,
                new WorldRecipients(positions,recipients,alternators.recipients(),groups,networks,environment.recipients(),ownedBlockers,wires));
    }
    private void retainPorts(DeviceId id,SimulatedDevice device,List<DeviceInput> descriptors,Map<InWorldNode,PortId> ports) {
        var latest=previous.get();
        if(latest==null)return;
        latest.devices().stream().filter(d->d.id().equals(id)).findFirst().ifPresent(old->{
            descriptors.removeIf(d->d.id().equals(id));descriptors.add(old);
            old.ports().forEach(port->ports.put(new InWorldNode(port.terminal(),device.pos),port));
        });
    }
    private static Map<String,Double> thermal(double resistance,double capacity,double conductance,double trip) {
        return NativeProtection.thermal(resistance,capacity,conductance,trip);
    }
}
