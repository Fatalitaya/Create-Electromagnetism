package com.electromagnetism.simulation;

import java.util.*;
import com.electromagnetism.simulation.FaultDomainMigration.Domain;
import static com.electromagnetism.simulation.FaultDomainGraph.*;
import java.util.function.Function;
import static com.electromagnetism.simulation.SimulationProtocol.*;

/** Dimension owner. Native advancement runs synchronously on the server thread. */
public final class FaultDomainManager implements AutoCloseable {
    public record SavedDomain(Snapshot snapshot,byte[] history) {public SavedDomain {history=history.clone();}}
    public record Saved(List<SavedDomain> domains) {public Saved {domains=List.copyOf(domains);}}
    private final SimulationBackend backend;
    private final List<Domain> domains=new ArrayList<>();
    private boolean closed;
    private final FaultDomainMigration migration;
    private double capturedTime;
    private final Set<DeviceId> barrierMembers=new HashSet<>();

    public FaultDomainManager(SimulationBackend backend,Saved restored) {
        this.backend=backend;
        migration=new FaultDomainMigration(backend,Runnable::run,domains,barrierMembers);
        if(restored!=null) {
            for(var saved:restored.domains()) {
                var coordinator=new SimulationCoordinator(backend,Runnable::run,Integer.MAX_VALUE,Long.MAX_VALUE,saved.snapshot(),saved.history());
                var frame=coordinator.lastRecordedFrame();domains.add(new Domain(coordinator,identities(frame)));
                capturedTime=Math.max(capturedTime,coordinator.progress().targetTime()+coordinator.progress().excludedSeconds());
            }
        }
    }
    private List<Set<DeviceId>> groups(SimulationCoordinator.Frame frame,boolean acceptedDependencies) {
        return FaultDomainGraph.groups(frame,acceptedDependencies?domains.stream().map(domain->domain.coordinator().acceptedFrame()).toList():List.of());
    }
    private boolean configure(SimulationCoordinator.Frame frame){return migration.configure(groups(frame,true));}
    public void tick(SimulationCoordinator.Frame frame) {
        if(backend.availability().status()!=Status.READY)return;
        // Retire last tick's failed inputs before topology repartition checks.
        domains.forEach(domain->domain.coordinator().beginTick());
        boolean configured=configure(frame);
        for(var domain:domains)if(configured || Collections.disjoint(domain.members(),barrierMembers))
            domain.coordinator().tick(slice(frame,domain.members()));
        capturedTime=domains.stream().mapToDouble(d->d.coordinator().progress().targetTime()).max().orElse(capturedTime);
    }
    public void endTick(SimulationCoordinator.Frame live,Function<Observations,List<UUID>> sink) {
        
        for(var domain:List.copyOf(domains)) {
            domain.coordinator().finishTick(slice(live,domain.members()),sink);
        }
    }
    public void pump(SimulationCoordinator.Frame live){for(var domain:domains)domain.coordinator().pump(slice(live,domain.members()));}
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
        var result=new HashSet<DeviceId>();
        for(var domain:domains) {
            var status=domain.coordinator().status().status();
            if(status==Status.REJECTED || status==Status.UNSUPPORTED || status==Status.UNAVAILABLE || !domain.coordinator().progress().pauseReason().isEmpty())result.addAll(domain.members());
        }
        return result;
    }
    public List<SimulationCoordinator.Progress> domainProgress(){return domains.stream().map(d->d.coordinator().progress()).toList();}
    public SimulationCoordinator.Progress progress(){
        var progress=domainProgress();double accepted=progress.stream().mapToDouble(SimulationCoordinator.Progress::acceptedTime).min().orElse(0);
        return new SimulationCoordinator.Progress(accepted,capturedTime,Math.max(0,capturedTime-accepted),progress.stream().mapToDouble(SimulationCoordinator.Progress::excludedSeconds).max().orElse(0),
                progress.stream().mapToInt(SimulationCoordinator.Progress::queuedBoundaries).sum(),progress.stream().mapToInt(SimulationCoordinator.Progress::encodedBytes).sum(),
                migration.failure()==null?"":migration.failure().reason(),-1,-1,progress.stream().flatMap(p->p.pendingEvents().stream()).toList());
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
            var snapshot=coordinator.checkpoint(slice(live,domain.members()),sink);saved.add(new SavedDomain(snapshot,coordinator.inputHistoryCheckpoint()));
        }
        return new Saved(saved);
    }
    @Override public void close(){if(closed)return;domains.forEach(d->d.coordinator().close());closed=true;}
}
