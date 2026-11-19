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
    private final SimulationFaultLog faults=new SimulationFaultLog();
    private boolean failed(Diagnostic diagnostic,Throwable cause) {
        boundaryFailure=diagnostic;
        faults.report("Repartitioning electrical domains "+domains.stream().map(d->d.coordinator().revision()).toList(),diagnostic,cause);
        return false;
    }

    FaultDomainMigration(SimulationBackend backend,Executor workers,List<Domain> domains,Set<DeviceId> barrierMembers) {
        this.backend=backend;this.workers=workers;this.domains=domains;this.barrierMembers=barrierMembers;
    }
    Diagnostic failure(){return boundaryFailure;}
    void configure(List<Set<DeviceId>> groups) {
        barrierMembers.clear();
        boundaryFailure=null;
        var sources=new ArrayList<>(domains);
        var targets=new ArrayList<>(groups);
        for(var domain:domains)if(targets.remove(domain.members()))sources.remove(domain);

        // Migrate each connected component of overlapping old and new owners.
        // Finish retirements and merges before allocating independent sessions.
        while(!sources.isEmpty()) {
            var component=new ArrayList<Domain>();
            component.add(sources.removeFirst());
            var members=new HashSet<>(component.getFirst().members());
            var destinations=new ArrayList<Set<DeviceId>>();
            boolean expanded;
            do {
                expanded=false;
                for(var it=targets.iterator();it.hasNext();) {
                    var target=it.next();
                    if(Collections.disjoint(members,target))continue;
                    destinations.add(target);members.addAll(target);it.remove();expanded=true;
                }
                for(var it=sources.iterator();it.hasNext();) {
                    var source=it.next();
                    if(Collections.disjoint(members,source.members()))continue;
                    component.add(source);members.addAll(source.members());it.remove();expanded=true;
                }
            } while(expanded);
            if(destinations.isEmpty() && component.stream().allMatch(d->d.coordinator().canRepartition()
                    && d.coordinator().acceptedFrame().branches().isEmpty()
                    && d.coordinator().acceptedFrame().devices().isEmpty())) {
                component.forEach(d->d.coordinator().close());
                domains.removeAll(component);
            } else if(destinations.isEmpty() || !migrate(component,destinations))barrierMembers.addAll(members);
        }
        for(var target:targets)if(!migrate(List.of(),List.of(target)))barrierMembers.addAll(target);
        if(boundaryFailure==null)faults.recovered();
    }

    private boolean migrate(List<Domain> sources,List<Set<DeviceId>> destinations) {
        if(sources.isEmpty()) {
            // A disconnected new circuit has no accepted state to migrate from another circuit.
            var empty=new SimulationCoordinator.Frame(List.of(),List.of(),List.of(),RemovalDisposition.EXPORT_STORED_ENERGY);
            var members=destinations.getFirst();
            try {
                var revision=new Revision(backend.create(),0,0,0);
                var owner=new SimulationCoordinator(backend,workers,SimulationCoordinator.UNLIMITED_STEPS,
                        SimulationCoordinator.UNLIMITED_NANOS,revision,0,empty);
                domains.add(new Domain(owner,members));
            } catch(SimulationFailure failure) {
                return failed(failure.diagnostic(),failure);
            }
            return true;
        }
        for(var source:sources)if(!source.coordinator().canRepartition())
            return failed(new Diagnostic(Status.REJECTED,"Cannot transfer unconfirmed circuit state: "+source.coordinator().progress()),null);
        // Rebase frozen states onto the newest boundary without simulating missing inputs.
        double time=sources.stream().mapToDouble(d->d.coordinator().acceptedTime()).max().orElseThrow();
        final Prepared prepared;
        try {prepared=prepare(sources,destinations,time);}
        catch(SimulationFailure invalid){return failed(invalid.diagnostic(),invalid);}
        catch(IllegalArgumentException invalid){return failed(new Diagnostic(Status.REJECTED,invalid.getMessage()),invalid);}
        try {backend.repartition(new Repartition(sources.stream().map(d->d.coordinator().revision()).toList(),prepared.targets()));}
        catch(SimulationFailure failed){return failed(failed.diagnostic(),failed);}
        sources.forEach(d->d.coordinator().relinquish());domains.removeAll(sources);
        domains.addAll(prepared.replacements());
        return true;
    }

    private record Prepared(List<Partition> targets,List<Domain> replacements) {}
    private Prepared prepare(List<Domain> sources,List<Set<DeviceId>> destinations,double time) {
        var previous=combine(sources.stream().map(d->d.coordinator().acceptedFrame()).toList());
        var frames=destinations.stream().map(g->slice(previous,g)).map(f->new SimulationCoordinator.Frame(f.devices(),f.branches(),f.blockers(),f.removalDisposition(),List.of(),f.recipients())).toList();
        var targets=frames.stream().map(f->new Partition(new Revision(UUID.randomUUID(),0,0,0),NativeWireCodec.compile(new Topology(new Revision(new UUID(0,0),0,0,0),f.devices(),f.branches(),f.removalDisposition())).stream().map(Branch::id).toList())).toList();
        var replacements=new ArrayList<Domain>();
        double excluded=Math.max(0,sources.stream().mapToDouble(d->d.coordinator().progress().targetTime()+d.coordinator().progress().excludedSeconds()).max().orElse(time)-time);
        for(int index=0;index<destinations.size();index++)replacements.add(new Domain(
                new SimulationCoordinator(backend,workers,SimulationCoordinator.UNLIMITED_STEPS,SimulationCoordinator.UNLIMITED_NANOS,targets.get(index).revision(),time,frames.get(index),excluded),destinations.get(index)));
        return new Prepared(targets,List.copyOf(replacements));
    }
}
