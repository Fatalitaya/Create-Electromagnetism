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
    public record SavedDomain(Snapshot snapshot,byte[] history,boolean tickBoundary,Set<DeviceId> members) {
        public SavedDomain(Snapshot snapshot,byte[] history,boolean tickBoundary){this(snapshot,history,tickBoundary,null);}
        public SavedDomain {history=history.clone();members=members==null?null:Set.copyOf(members);}
    }
    public record Saved(List<SavedDomain> domains) {
        public Saved {domains=List.copyOf(domains);}
    }
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
    private final Set<DeviceId> quarantined=new HashSet<>();
    private boolean unknownQuarantine;
    private Diagnostic restoreFailure;
    private Diagnostic inputFailure;
    private final Set<DeviceId> barrierMembers=new HashSet<>();

    public FaultDomainManager(SimulationBackend backend,Saved restored) {
        this.backend=backend;
        migration=new FaultDomainMigration(backend,WORKERS,domains,barrierMembers);
        if(restored!=null)for(var saved:restored.domains())restore(saved);
    }

    public void restore(SavedDomain saved) {
        var coordinator=new SimulationCoordinator(backend,WORKERS,SimulationCoordinator.UNLIMITED_STEPS,
                SimulationCoordinator.UNLIMITED_NANOS,saved.snapshot(),saved.history(),saved.tickBoundary(),true);
        var members=saved.members()==null?identities(coordinator.acceptedFrame()):saved.members();
        domains.add(new Domain(coordinator,members));
    }

    public void quarantine(Set<DeviceId> members,String reason) {
        restoreFailure=new Diagnostic(Status.REJECTED,reason);
        if(members==null)unknownQuarantine=true;
        else quarantined.addAll(members);
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
    public void beginWorldTick(long tick) {
        worldTick=tick;
        for(var domain:domains) {
            domain.coordinator().startWorldTick(tick);
            domain.coordinator().beginTick();
        }
    }
    public void tick(SimulationCoordinator.Frame frame,Function<Observations,List<UUID>> sink) {
        if(backend.availability().status()!=Status.READY)return;
        var groups=FaultDomainGraph.groups(frame,domains.stream().map(d->d.coordinator().acceptedFrame()).toList());
        var known=new HashSet<DeviceId>();
        if(unknownQuarantine)domains.forEach(domain->known.addAll(domain.members()));
        var eligible=new LinkedHashMap<Set<DeviceId>,SimulationCoordinator.Frame>();
        var frozen=new HashSet<DeviceId>();
        inputFailure=null;
        for(var group:groups) {
            if(!Collections.disjoint(group,quarantined) || (unknownQuarantine && !known.containsAll(group))) {
                frozen.addAll(group);
                continue;
            }
            var input=slice(frame,group);
            if(!input.blockers().isEmpty()) {
                inputFailure=new Diagnostic(Status.UNSUPPORTED,String.join("; ",input.blockers()));
                frozen.addAll(group);
            } else eligible.put(group,input);
        }
        // Preserve the last accepted state of every affected source, including
        // blocked splits whose other destinations have not yet migrated.
        var frozenOwners=domains.stream().filter(domain->!Collections.disjoint(domain.members(),frozen)).toList();
        for(var domain:frozenOwners)frozen.addAll(domain.members());
        eligible.keySet().removeIf(group->!Collections.disjoint(group,frozen));
        var desired=new ArrayList<>(eligible.keySet());
        frozenOwners.forEach(domain->desired.add(domain.members()));
        migration.configure(desired);
        barrierMembers.addAll(frozen);
        var active=domains.stream().filter(d->eligible.containsKey(d.members()) && Collections.disjoint(d.members(),barrierMembers))
                .sorted(Comparator.comparingLong((Domain d)->d.coordinator().lastSolveNanos()).reversed()).toList();
        var live=frame;
        for(int first=0;first<active.size();first+=PARALLELISM) {
            var batch=active.subList(first,Math.min(first+PARALLELISM,active.size()));
            for(var domain:batch) {
                domain.coordinator().startWorldTick(worldTick);
                var frozenInput=eligible.get(domain.members());
                if(live==frame || frozenInput.equals(slice(live,domain.members())))
                    domain.coordinator().tick(frozenInput,worldTick);
            }
            for(var domain:batch) {
                var current=live==frame?eligible.get(domain.members()):slice(live,domain.members());
                var updated=domain.coordinator().finishLiveTick(current,sink);
                if(updated!=null)live=updated;
            }
        }
    }

    public static Set<DeviceId> changedBindings(SimulationCoordinator.Frame previous,SimulationCoordinator.Frame live) {
        if(previous==null)return identities(live);
        var affected=new HashSet<DeviceId>();
        for(var members:FaultDomainGraph.groups(live,List.of(previous))) {
            var before=slice(previous,members);
            var after=slice(live,members);
            if(!SimulationCoordinator.sameBindings(after,before) || !after.waveforms().equals(before.waveforms())
                    || !after.blockers().equals(before.blockers()) || !before.recipients().equals(after.recipients()))
                affected.addAll(members);
        }
        return affected;
    }

    public Set<DeviceId> membersForBranch(UUID branch) {
        for(var domain:domains)if(domain.coordinator().acceptedBranch(branch).isPresent())return domain.members();
        return Set.of();
    }

    public boolean readyForDelivery(){return domains.stream().anyMatch(d->d.coordinator().readyForDelivery());}
    public void endTick(SimulationCoordinator.Frame live,Function<Observations,List<UUID>> sink) {
        for(var domain:List.copyOf(domains)) {
            var updated=domain.coordinator().finishLiveTick(slice(live,domain.members()),sink);
            if(updated!=null)live=updated;
        }
    }
    public SimulationCoordinator.Frame lastRecordedFrame(){return combine(domains.stream().map(d->d.coordinator().lastRecordedFrame()).toList());}
    public boolean canExchangeEnergy(){return domains.stream().anyMatch(d->Collections.disjoint(d.members(),barrierMembers) && d.coordinator().canExchangeEnergy());}
    public boolean exchangeEnergy(SimulationCoordinator.Frame live,UUID branch,double joules,double maximum,boolean commit){
        for(var domain:domains)if(Collections.disjoint(domain.members(),barrierMembers) && domain.coordinator().acceptedBranch(branch).isPresent())return domain.coordinator().exchangeEnergy(slice(live,domain.members()),branch,joules,maximum,commit);
        return false;
    }
    public Diagnostic status(){
        if(closed)return new Diagnostic(Status.CLOSED,"Fault domains closed");
        if(restoreFailure!=null)return restoreFailure;
        if(inputFailure!=null)return inputFailure;
        if(migration.failure()!=null)return migration.failure();
        for(var domain:domains){var status=domain.coordinator().status();if(status.status()!=Status.READY)return status;}
        return backend.availability();
    }
    public Set<DeviceId> faultedRecipients() {
        var result=new HashSet<DeviceId>(barrierMembers);
        for(var domain:domains) {
            if(domain.coordinator().faulted())result.addAll(domain.members());
        }
        return result;
    }
    public record TickReport(long tick,List<SimulationCoordinator.TickProgress> domains,Diagnostic diagnostic) {}
    public TickReport progress(){
        return new TickReport(worldTick,domains.stream().map(d->d.coordinator().tickProgress()).toList(),status());
    }
    public Saved checkpoint() {
        var saved=new ArrayList<SavedDomain>();
        for(var domain:domains) {
            var coordinator=domain.coordinator();
            saved.add(new SavedDomain(coordinator.acceptedSnapshot(),coordinator.acceptedBoundary(),true,domain.members()));
        }
        return new Saved(saved);
    }
    @Override public void close(){if(closed)return;domains.forEach(d->d.coordinator().close());closed=true;}
}
