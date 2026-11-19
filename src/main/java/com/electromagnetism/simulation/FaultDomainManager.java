package com.electromagnetism.simulation;

import java.util.*;
import com.electromagnetism.simulation.FaultDomainMigration.Domain;
import static com.electromagnetism.simulation.FaultDomainGraph.*;
import java.util.function.Function;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import static com.electromagnetism.simulation.SimulationProtocol.*;

/** Dimension owner. Independent numerical sessions advance concurrently within the world tick. */
public final class FaultDomainManager implements AutoCloseable {
    public record SavedDomain(Snapshot snapshot,byte[] history,boolean tickBoundary) {public SavedDomain {history=history.clone();}}
    public record Saved(List<SavedDomain> domains) {public Saved {domains=List.copyOf(domains);}}
    private static final int PARALLELISM = Math.clamp(Integer.getInteger("electromagnetism.solverWorkers",
            Runtime.getRuntime().availableProcessors() - 1), 1, 64);
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(PARALLELISM, PARALLELISM,
            30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(PARALLELISM),
            Thread.ofPlatform().daemon().name("electromagnetism-solver-", 0).factory(),
            (task, pool) -> {
                try {
                    pool.getQueue().put(task);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.util.concurrent.RejectedExecutionException(interrupted);
                }
            });
    static { WORKERS.allowCoreThreadTimeOut(true); }

    private final SimulationBackend backend;
    private final List<Domain> domains=new ArrayList<>();
    private boolean closed;
    private final FaultDomainMigration migration;
    private long worldTick;
    private final Set<DeviceId> barrierMembers=new HashSet<>();

    public FaultDomainManager(SimulationBackend backend,Saved restored) {
        this.backend=backend;
        migration=new FaultDomainMigration(backend,WORKERS,domains,barrierMembers);
        if(restored!=null) {
            for(var saved:restored.domains()) {
                var coordinator=new SimulationCoordinator(backend,WORKERS,SimulationCoordinator.UNLIMITED_STEPS,SimulationCoordinator.UNLIMITED_NANOS,saved.snapshot(),saved.history(),saved.tickBoundary());
                var frame=coordinator.acceptedFrame();domains.add(new Domain(coordinator,identities(frame)));
            }
        }
    }
    public Map<UUID,Set<UUID>> pendingEvents() {
        var pending=new HashMap<UUID,Set<UUID>>();
        for(var domain:domains) {
            var coordinator=domain.coordinator();
            var events=coordinator.progress().pendingEvents();
            if(!events.isEmpty())pending.put(coordinator.revision().session(),Set.copyOf(events));
        }
        return pending;
    }
    private List<Set<DeviceId>> groups(SimulationCoordinator.Frame frame,boolean acceptedDependencies) {
        return FaultDomainGraph.groups(frame,acceptedDependencies?domains.stream().map(domain->domain.coordinator().acceptedFrame()).toList():List.of());
    }
    private boolean configure(SimulationCoordinator.Frame frame){return migration.configure(groups(frame,true));}

    public void beginWorldTick(long tick) {
        worldTick=tick;
        for(var domain:domains) {
            domain.coordinator().startWorldTick(tick);
            domain.coordinator().beginTick();
        }
    }
    public void tick(SimulationCoordinator.Frame frame) {
        if(backend.availability().status()!=Status.READY)return;
        boolean configured=configure(frame);
        var dispatch = new ArrayList<>(domains);
        dispatch.sort(Comparator.comparingLong((Domain d) -> d.coordinator().lastSolveNanos()).reversed());
        for(var domain:dispatch) {
            domain.coordinator().startWorldTick(worldTick);
            if(configured || Collections.disjoint(domain.members(),barrierMembers))
                domain.coordinator().tick(slice(frame,domain.members()));
        }
        for (var domain : dispatch) domain.coordinator().awaitNumericalWork();
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
