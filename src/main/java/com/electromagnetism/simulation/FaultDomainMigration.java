package com.electromagnetism.simulation;

import java.util.*;
import java.util.concurrent.Executor;
import static com.electromagnetism.simulation.FaultDomainGraph.*;
import static com.electromagnetism.simulation.SimulationProtocol.*;

final class FaultDomainMigration {
    record Domain(SimulationCoordinator coordinator,Set<DeviceId> members) {
        Domain {members=Set.copyOf(members);}
    }
    private final SimulationBackend backend;
    private final Executor workers;
    private final List<Domain> domains;
    private final Set<DeviceId> barrierMembers;
    private Diagnostic boundaryFailure;

    FaultDomainMigration(SimulationBackend backend,Executor workers,List<Domain> domains,Set<DeviceId> barrierMembers) {
        this.backend=backend;this.workers=workers;this.domains=domains;this.barrierMembers=barrierMembers;
    }
    Diagnostic failure(){return boundaryFailure;}
    void clearFailure(){boundaryFailure=null;}
    private boolean matches(List<Set<DeviceId>> groups){return new HashSet<>(groups).equals(domains.stream().map(Domain::members).collect(java.util.stream.Collectors.toSet()));}
    boolean configure(List<Set<DeviceId>> groups) {
        barrierMembers.clear();
        if(matches(groups)){boundaryFailure=null;return true;}
        if(groups.size()>64){domains.forEach(d->barrierMembers.addAll(d.members()));boundaryFailure=new Diagnostic(Status.PARTIAL,"Fault-domain capacity exceeded");return false;}
        if(domains.isEmpty()) {
            var empty=new SimulationCoordinator.Frame(List.of(),List.of(),List.of(),RemovalDisposition.EXPORT_STORED_ENERGY);
            var id=backend.create();domains.add(new Domain(new SimulationCoordinator(backend,workers,Integer.MAX_VALUE,Long.MAX_VALUE,new Revision(id,0,0,0),0,empty),Set.of()));
        }
        if(matches(groups)){boundaryFailure=null;return true;}
        var unchanged=domains.stream().filter(d->groups.contains(d.members())).toList();
        var sources=new ArrayList<>(domains);sources.removeAll(unchanged);
        var destinations=new ArrayList<>(groups);unchanged.forEach(d->destinations.remove(d.members()));
        // A newly appearing independent component needs a native empty destination at
        // this time boundary. An idle donor transfers no storage into that destination.
        if(sources.isEmpty() && !destinations.isEmpty()) {
            var donor=domains.stream().filter(d->d.coordinator().canExchangeEnergy()).findFirst().orElse(domains.getFirst());
            sources.add(donor);destinations.add(donor.members());
        }
        if(destinations.isEmpty()) {
            var donor=unchanged.stream().filter(d->d.coordinator().canExchangeEnergy()).findFirst().orElse(null);
            if(donor==null)return false;
            sources.add(donor);destinations.add(donor.members());
        }
        sources.forEach(d->barrierMembers.addAll(d.members()));destinations.forEach(barrierMembers::addAll);
        if(sources.stream().anyMatch(d->!d.coordinator().canRepartition()))return false;
        // Rebase frozen states onto the newest boundary without simulating missing inputs.
        double time=sources.stream().mapToDouble(d->d.coordinator().acceptedTime()).max().orElseThrow();
        final Prepared prepared;
        try {prepared=prepare(sources,destinations,time);}
        catch(SimulationFailure invalid){boundaryFailure=invalid.diagnostic();return false;}
        catch(IllegalArgumentException invalid){boundaryFailure=new Diagnostic(Status.REJECTED,invalid.getMessage());return false;}
        try {backend.repartition(new Repartition(time,sources.stream().map(d->d.coordinator().revision()).toList(),prepared.targets()));}
        catch(SimulationFailure failed){boundaryFailure=failed.diagnostic();return false;}
        sources.forEach(d->d.coordinator().relinquish());domains.removeAll(sources);
        domains.addAll(prepared.replacements());
        boundaryFailure=null;barrierMembers.clear();return true;
    }

    private record Prepared(List<Partition> targets,List<Domain> replacements) {}
    private Prepared prepare(List<Domain> sources,List<Set<DeviceId>> destinations,double time) {
        var previous=combine(sources.stream().map(d->d.coordinator().acceptedFrame()).toList());
        var frames=destinations.stream().map(g->slice(previous,g)).map(f->new SimulationCoordinator.Frame(f.devices(),f.branches(),f.blockers(),f.removalDisposition(),List.of(),f.recipients())).toList();
        var targets=frames.stream().map(f->new Partition(new Revision(UUID.randomUUID(),0,0,0),NativeWireCodec.compile(new Topology(new Revision(new UUID(0,0),0,0,0),f.devices(),f.branches(),f.removalDisposition())).stream().map(Branch::id).toList())).toList();
        var replacements=new ArrayList<Domain>();
        double excluded=Math.max(0,sources.stream().mapToDouble(d->d.coordinator().progress().targetTime()+d.coordinator().progress().excludedSeconds()).max().orElse(time)-time);
        for(int index=0;index<destinations.size();index++)replacements.add(new Domain(
                new SimulationCoordinator(backend,workers,Integer.MAX_VALUE,Long.MAX_VALUE,targets.get(index).revision(),time,frames.get(index),excluded),destinations.get(index)));
        return new Prepared(targets,List.copyOf(replacements));
    }
}
