package com.electromagnetism.simulation;

import java.util.*;
import com.electromagnetism.simulation.FaultDomainMigration.Domain;
import static com.electromagnetism.simulation.FaultDomainGraph.*;
import java.util.function.Function;
import static com.electromagnetism.simulation.SimulationProtocol.*;

/** Dimension owner. Native advancement runs synchronously on the server thread. */
public final class FaultDomainManager implements AutoCloseable {
    public record SavedDomain(Snapshot snapshot,byte[] history,boolean tickBoundary) {public SavedDomain {history=history.clone();}}
    public record Saved(List<SavedDomain> domains) {public Saved {domains=List.copyOf(domains);}}
    private final SimulationBackend backend;
    private final List<Domain> domains=new ArrayList<>();
    private boolean closed;
    private final FaultDomainMigration migration;
    private long worldTick;
    private long deadlineNanos;
    private int firstDomain;
    private final Set<DeviceId> barrierMembers=new HashSet<>();

    public FaultDomainManager(SimulationBackend backend,Saved restored) {
        this.backend=backend;
        migration=new FaultDomainMigration(backend,Runnable::run,domains,barrierMembers);
        if(restored!=null) {
            for(var saved:restored.domains()) {
                var coordinator=new SimulationCoordinator(backend,Runnable::run,SimulationCoordinator.UNLIMITED_STEPS,SimulationCoordinator.UNLIMITED_NANOS,saved.snapshot(),saved.history(),saved.tickBoundary());
                var frame=coordinator.acceptedFrame();domains.add(new Domain(coordinator,identities(frame)));
            }
        }
    }
    private List<Set<DeviceId>> groups(SimulationCoordinator.Frame frame,boolean acceptedDependencies) {
        return FaultDomainGraph.groups(frame,acceptedDependencies?domains.stream().map(domain->domain.coordinator().acceptedFrame()).toList():List.of());
    }
    private boolean configure(SimulationCoordinator.Frame frame){return migration.configure(groups(frame,true));}

    public void beginWorldTick(long tick,long deadline) {
        worldTick=tick;
        deadlineNanos=deadline;
        for(var domain:domains) {
            domain.coordinator().startWorldTick(tick,deadline);
            domain.coordinator().beginTick();
        }
    }
    public void tick(SimulationCoordinator.Frame frame) {
        if(backend.availability().status()!=Status.READY)return;
        boolean configured=configure(frame);
        var ordered=List.copyOf(domains);
        for(int index=0;index<ordered.size();index++) {
            var domain=ordered.get((firstDomain+index)%ordered.size());
            domain.coordinator().startWorldTick(worldTick,deadlineNanos);
            if(configured || Collections.disjoint(domain.members(),barrierMembers))
                domain.coordinator().tick(slice(frame,domain.members()));
        }
        if(!ordered.isEmpty())firstDomain=(firstDomain+1)%ordered.size();
    }
    public void endTick(SimulationCoordinator.Frame live,Function<Observations,List<UUID>> sink) {
        
        for(var domain:List.copyOf(domains)) {
            domain.coordinator().finishTick(slice(live,domain.members()),sink);
        }
    }
    public void pump(SimulationCoordinator.Frame live){for(var domain:List.copyOf(domains))domain.coordinator().pump(slice(live,domain.members()));}
    public SimulationCoordinator.Frame lastRecordedFrame(){return combine(domains.stream().map(d->d.coordinator().lastRecordedFrame()).toList());}
    public boolean canExchangeEnergy(){return domains.stream().anyMatch(d->Collections.disjoint(d.members(),barrierMembers) && d.coordinator().canExchangeEnergy());}
    public boolean exchangeEnergy(SimulationCoordinator.Frame live,UUID branch,double joules,double maximum,boolean commit){
        for(var domain:domains)if(Collections.disjoint(domain.members(),barrierMembers) && domain.coordinator().acceptedBranch(branch).isPresent())return domain.coordinator().exchangeEnergy(slice(live,domain.members()),branch,joules,maximum,commit);
        return false;
    }
    public Diagnostic status(){
        if(closed)return new Diagnostic(Status.CLOSED,"Fault domains closed");
        if(migration.failure()!=null)return migration.failure();
        for(var domain:domains){var status=domain.coordinator().status();if(status.status()!=Status.READY)return status;}
        return backend.availability();
    }
    public Set<DeviceId> faultedRecipients() {
        var result=new HashSet<DeviceId>(barrierMembers);
        for(var domain:domains) {
            var status=domain.coordinator().status().status();
            if(status==Status.PARTIAL || status==Status.REJECTED || status==Status.UNSUPPORTED || status==Status.UNAVAILABLE || !domain.coordinator().progress().pauseReason().isEmpty())result.addAll(domain.members());
        }
        return result;
    }
    /** A transformer readout may hold its last accepted RMS window for one game
     * second during ordinary budget skips. Other faults revoke it immediately. */
    public Set<DeviceId> recentBudgetSkips() {
        var result = new HashSet<DeviceId>();
        for (var domain : domains) {
            var tick = domain.coordinator().tickProgress();
            var diagnostic = tick.diagnostic();
            if (tick.lastCompletedTick() >= 0 && tick.tick() - tick.lastCompletedTick() <= 20
                    && diagnostic.status() == Status.PARTIAL
                    && (diagnostic.reason().startsWith("Tick budget exhausted")
                        || diagnostic.reason().equals("Cooperative time budget exhausted"))) {
                result.addAll(domain.members());
            }
        }
        result.removeAll(barrierMembers);
        return result;
    }
    public record TickReport(long tick,List<SimulationCoordinator.TickProgress> domains,Diagnostic diagnostic) {}
    public TickReport progress(){
        return new TickReport(worldTick,domains.stream().map(d->d.coordinator().tickProgress()).toList(),status());
    }
    public void resumeMissingInputs(SimulationCoordinator.Frame live){
        for(var domain:domains)if(domain.coordinator().progress().missingStart()>=0)domain.coordinator().resumeMissingInputs(slice(live,domain.members()));
        migration.clearFailure();
    }
    public Saved checkpoint(SimulationCoordinator.Frame live,Function<Observations,List<UUID>> sink) {
        endTick(live,sink);
        var saved=new ArrayList<SavedDomain>();
        for(var domain:domains) {
            var coordinator=domain.coordinator();
            var snapshot=coordinator.checkpoint(slice(live,domain.members()),sink);saved.add(new SavedDomain(snapshot,coordinator.tickBoundaryCheckpoint(),true));
        }
        return new Saved(saved);
    }
    @Override public void close(){if(closed)return;domains.forEach(d->d.coordinator().close());closed=true;}
}
