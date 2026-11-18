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
    static SimulationCoordinator.Frame slice(SimulationCoordinator.Frame frame,Set<DeviceId> members) {
        var recipients=frame.recipients();var positions=new HashMap<DeviceId,Long>();var owners=new HashMap<UUID,DeviceId>();
        var networks=new HashMap<DeviceId,Long>();var damage=new HashMap<DeviceId,List<DeviceId>>();
        recipients.positions().forEach((id,pos)->{if(members.contains(id))positions.put(id,pos);});
        recipients.branches().forEach((id,owner)->{if(members.contains(owner))owners.put(id,owner);});
        recipients.mechanicalNetworks().forEach((id,network)->{if(members.contains(id))networks.put(id,network);});
        recipients.damageGroups().forEach((id,group)->{if(members.contains(id))damage.put(id,group);});
        var blockers=new ArrayList<>(frame.blockers());recipients.blockers().forEach((id,reasons)->{if(members.contains(id))blockers.addAll(reasons);});
        var wires=new HashMap<UUID,WorldRecipients.Wire>();frame.branches().stream().filter(b->members.contains(b.positive().device())).forEach(b->{if(recipients.wires().containsKey(b.id()))wires.put(b.id(),recipients.wires().get(b.id()));});
        var effects=new HashMap<UUID,UUID>();frame.branches().stream().filter(b->members.contains(b.positive().device())).forEach(b->{if(recipients.effects().containsKey(b.id()))effects.put(b.id(),recipients.effects().get(b.id()));});
        return new SimulationCoordinator.Frame(frame.devices().stream().filter(d->members.contains(d.id())).toList(),
                frame.branches().stream().filter(b->members.contains(b.positive().device())).toList(),blockers,frame.removalDisposition(),
                frame.waveforms().stream().filter(w->members.contains(w.positive().device())).toList(),
                new WorldRecipients(positions,owners,recipients.assemblies().stream().filter(a->members.contains(new DeviceId(a.identity(),0))).toList(),damage,networks,effects,Map.of(),wires));
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
