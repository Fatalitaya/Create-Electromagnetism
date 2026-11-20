package com.electromagnetism.simulation;

import java.util.*;
import static com.electromagnetism.simulation.SimulationProtocol.*;

final class FaultDomainGraph {
    private FaultDomainGraph() {}
    static Set<DeviceId> identities(SimulationCoordinator.Frame frame) {
        if(frame==null)return Set.of();
        var result=new HashSet<DeviceId>();frame.devices().forEach(d->result.add(d.id()));
        result.addAll(frame.recipients().mechanicalNetworks().keySet());
        for(var a:frame.recipients().assemblies()){result.add(new DeviceId(a.identity(),0));a.members().forEach(id->result.add(new DeviceId(id,0)));}
        frame.recipients().effects().values().forEach(id->result.add(new DeviceId(id,0)));
        result.addAll(frame.recipients().blockers().keySet());
        return Set.copyOf(result);
    }
    private static DeviceId root(Map<DeviceId,DeviceId> parents,DeviceId id) {
        parents.putIfAbsent(id,id);var parent=parents.get(id);
        if(!parent.equals(id)){parent=root(parents,parent);parents.put(id,parent);}return parent;
    }
    private static void union(Map<DeviceId,DeviceId> parents,DeviceId a,DeviceId b){parents.put(root(parents,a),root(parents,b));}
    private static void dependencies(Map<DeviceId,DeviceId> parents,SimulationCoordinator.Frame frame) {
        if(frame==null)return;
        frame.devices().forEach(d->root(parents,d.id()));
        frame.recipients().blockers().keySet().forEach(id->root(parents,id));
        for(var b:frame.branches())if(frame.recipients().effects().containsKey(b.id()))union(parents,b.positive().device(),new DeviceId(frame.recipients().effects().get(b.id()),0));
        for(var waveform:frame.waveforms())union(parents,waveform.positive().device(),waveform.negative().device());
        for(var b:frame.branches()) {
            union(parents,b.positive().device(),b.negative().device());
            b.additionalPorts().forEach(p->union(parents,b.positive().device(),p.device()));
        }
        var networks=new HashMap<Long,DeviceId>();
        frame.recipients().mechanicalNetworks().forEach((id,network)->{
            var previous=networks.putIfAbsent(network,id);if(previous!=null)union(parents,id,previous);
        });
        frame.recipients().damageGroups().forEach((id,members)->members.forEach(member->union(parents,id,member)));
        for(var assembly:frame.recipients().assemblies())for(UUID member:assembly.members())
            union(parents,new DeviceId(assembly.identity(),0),new DeviceId(member,0));
    }
    static List<Set<DeviceId>> groups(SimulationCoordinator.Frame frame,List<SimulationCoordinator.Frame> acceptedFrames) {
        var parents=new HashMap<DeviceId,DeviceId>();dependencies(parents,frame);
        for(var accepted:acceptedFrames)dependencies(parents,accepted);
        var groups=new HashMap<DeviceId,Set<DeviceId>>();
        for(var id:List.copyOf(parents.keySet()))groups.computeIfAbsent(root(parents,id),ignored->new HashSet<>()).add(id);
        if(groups.isEmpty())return List.of(Set.of());
        return groups.values().stream().map(Set::copyOf).sorted(Comparator.comparing(g->g.stream().map(DeviceId::instance).min(UUID::compareTo).orElse(new UUID(0,0)))).toList();
    }
    private static final class Slice {
        final List<DeviceInput> devices = new ArrayList<>();
        final List<Branch> branches = new ArrayList<>();
        final List<WaveformChannel> waveforms = new ArrayList<>();
        final List<String> blockers;
        final Map<DeviceId,Long> positions = new HashMap<>();
        final Map<UUID,DeviceId> owners = new HashMap<>();
        final List<WorldRecipients.Assembly> assemblies = new ArrayList<>();
        final Map<DeviceId,List<DeviceId>> damage = new HashMap<>();
        final Map<DeviceId,Long> networks = new HashMap<>();
        final Map<UUID,UUID> effects = new HashMap<>();
        final Map<UUID,WorldRecipients.Wire> wires = new HashMap<>();

        Slice(List<String> globalBlockers) {
            blockers = new ArrayList<>(globalBlockers);
        }

        SimulationCoordinator.Frame frame(RemovalDisposition removal) {
            return new SimulationCoordinator.Frame(devices, branches, blockers, removal, waveforms,
                    new WorldRecipients(positions, owners, assemblies, damage, networks, effects, Map.of(), wires));
        }
    }

    private static void distribute(SimulationCoordinator.Frame frame, java.util.function.Function<DeviceId,Slice> owner) {
        var recipients = frame.recipients();
        for (var device : frame.devices()) {
            var slice = owner.apply(device.id());
            if (slice != null) slice.devices.add(device);
        }
        for (var branch : frame.branches()) {
            var slice = owner.apply(branch.positive().device());
            if (slice == null) continue;
            slice.branches.add(branch);
            var wire = recipients.wires().get(branch.id());
            if (wire != null) slice.wires.put(branch.id(), wire);
            var effect = recipients.effects().get(branch.id());
            if (effect != null) slice.effects.put(branch.id(), effect);
        }
        for (var channel : frame.waveforms()) {
            var slice = owner.apply(channel.positive().device());
            if (slice != null) slice.waveforms.add(channel);
        }
        for (var assembly : recipients.assemblies()) {
            var slice = owner.apply(new DeviceId(assembly.identity(), 0));
            if (slice != null) slice.assemblies.add(assembly);
        }
        recipients.positions().forEach((id, pos) -> {
            var slice = owner.apply(id);
            if (slice != null) slice.positions.put(id, pos);
        });
        recipients.branches().forEach((id, device) -> {
            var slice = owner.apply(device);
            if (slice != null) slice.owners.put(id, device);
        });
        recipients.mechanicalNetworks().forEach((id, network) -> {
            var slice = owner.apply(id);
            if (slice != null) slice.networks.put(id, network);
        });
        recipients.damageGroups().forEach((id, members) -> {
            var slice = owner.apply(id);
            if (slice != null) slice.damage.put(id, members);
        });
        recipients.blockers().forEach((id, reasons) -> {
            var slice = owner.apply(id);
            if (slice != null) slice.blockers.addAll(reasons);
        });
    }

    static SimulationCoordinator.Frame slice(SimulationCoordinator.Frame frame, Set<DeviceId> members) {
        var slice = new Slice(frame.blockers());
        distribute(frame, id -> members.contains(id) ? slice : null);
        return slice.frame(frame.removalDisposition());
    }

    static List<SimulationCoordinator.Frame> slices(SimulationCoordinator.Frame frame, List<Set<DeviceId>> groups) {
        if (groups.isEmpty()) return List.of();
        if (groups.size() == 1) return List.of(slice(frame, groups.getFirst()));
        var owners = new HashMap<DeviceId,Slice>();
        var slices = new ArrayList<Slice>(groups.size());
        for (var members : groups) {
            var slice = new Slice(frame.blockers());
            slices.add(slice);
            for (var id : members) {
                if (owners.put(id, slice) != null)
                    return groups.stream().map(group -> slice(frame, group)).toList();
            }
        }
        distribute(frame, owners::get);
        return slices.stream().map(slice -> slice.frame(frame.removalDisposition())).toList();
    }
    static SimulationCoordinator.Frame combine(List<SimulationCoordinator.Frame> frames) {
        var devices=new ArrayList<DeviceInput>();var branches=new ArrayList<Branch>();var waves=new ArrayList<WaveformChannel>();
        var positions=new HashMap<DeviceId,Long>();var owners=new HashMap<UUID,DeviceId>();var networks=new HashMap<DeviceId,Long>();
        var wires=new HashMap<UUID,WorldRecipients.Wire>();
        var effects=new HashMap<UUID,UUID>();var failures=new HashMap<DeviceId,List<String>>();
        var damage=new HashMap<DeviceId,List<DeviceId>>();var assemblies=new ArrayList<WorldRecipients.Assembly>();
        for(var frame:frames)if(frame!=null){devices.addAll(frame.devices());branches.addAll(frame.branches());waves.addAll(frame.waveforms());
            var r=frame.recipients();positions.putAll(r.positions());owners.putAll(r.branches());networks.putAll(r.mechanicalNetworks());damage.putAll(r.damageGroups());assemblies.addAll(r.assemblies());effects.putAll(r.effects());failures.putAll(r.blockers());wires.putAll(r.wires());}
        return new SimulationCoordinator.Frame(devices,branches,List.of(),RemovalDisposition.EXPORT_STORED_ENERGY,waves,new WorldRecipients(positions,owners,assemblies,damage,networks,effects,failures,wires));
    }
}
