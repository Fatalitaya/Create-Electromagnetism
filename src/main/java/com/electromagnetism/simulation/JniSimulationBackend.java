package com.electromagnetism.simulation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.electromagnetism.simulation.SimulationProtocol.*;
import static com.electromagnetism.simulation.NativeWireCodec.*;

/**
 * Each JNI session has its own handle. Java submits immutable requests, Rust advances physical
 * state, and the handshake uses installed mod metadata.
 */
public final class JniSimulationBackend implements SimulationBackend, AutoCloseable {
    private final String metadata;
    private final boolean adaptive;
    private final Diagnostic availability;
    private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lifecycle = new Object();

    private static final class Session {
        final long handle;
        final AtomicBoolean closing = new AtomicBoolean();
        Revision revision;
        double time;
        TickRequest inFlightTick;
        Writer inFlightBytes;
        Session(long handle, Revision revision) { this.handle=handle; this.revision=revision; }
    }

    @FunctionalInterface private interface Loader { void load() throws IOException; }
    private JniSimulationBackend(String metadata, Loader loader) {
        this(metadata,loader,false);
    }
    private JniSimulationBackend(String metadata, Loader loader,boolean adaptive) {
        this.metadata = Objects.requireNonNull(metadata);
        this.adaptive=adaptive;
        Diagnostic status;
        try {
            if (metadata.isBlank()) throw rejected("Missing mod metadata");
            loader.load();
            Reader info = exchange(request(metadata, 0, 0));
            if (!info.string().equals(metadata) || info.longInteger()!=1) throw rejected("Native capability or mod metadata mismatch");
            info.finish();
            status = new Diagnostic(Status.READY, "JNI circuit kernel loaded; world coverage must be validated separately");
        } catch (IOException | LinkageError | RuntimeException failure) {
            status = new Diagnostic(Status.UNAVAILABLE, "Native backend unavailable: " + failure.getMessage());
            new SimulationFaultLog().report("Loading JNI backend; mod metadata="+metadata,status,failure);
        }
        availability=status;
    }

    public static JniSimulationBackend packaged(String modVersion) {
        return new JniSimulationBackend(modVersion, NativeLinearBridge::loadPackaged);
    }
    /** Choose adaptive steps from charge, flux and energy error limits. */
    public static JniSimulationBackend adaptivePackaged(String modVersion) {
        return new JniSimulationBackend(modVersion, NativeLinearBridge::loadPackaged,true);
    }
    public static JniSimulationBackend library(Path library, String modVersion) {
        return new JniSimulationBackend(modVersion, () -> NativeLinearBridge.load(library));
    }
    @Override public Diagnostic availability() {
        return closed.get() ? new Diagnostic(Status.CLOSED,"Backend closed") : availability;
    }
    private void requireAvailable() {
        Diagnostic status=availability();
        if (status.status()!=Status.READY) throw new SimulationFailure(status.status(),status.reason());
    }
    private Session session(UUID id) {
        requireAvailable();
        Session session=sessions.get(id);
        if (session==null || session.closing.get()) throw new SimulationFailure(Status.CLOSED,"Unknown or closed session: "+id);
        return session;
    }
    private static void requireOpen(Session s) {
        if (s.closing.get()) throw new SimulationFailure(Status.CLOSED,"Session is closing");
    }

    @Override public UUID create() {
        UUID id=UUID.randomUUID(); create(id); return id;
    }
    private Session create(UUID id) {
        synchronized (lifecycle) {
            requireAvailable();
            Revision revision=new Revision(id,0,0,0);
            // Create the empty topology with explicit initial storage. Accuracy, source periods
            // and waveform sampling constrain subsequent steps.
            Writer w=request(metadata,adaptive?8:1,0).revision(revision).number(adaptive?0.05:0.0001);
            if(adaptive)w.number(1e-3).number(1e-6).number(1e-9).number(1e-15).number(1e-12).number(1e-9).number(1e-12);
            w.integer(1).integer(0);
            Reader result=exchange(w);
            long handle=result.longInteger(); result.finish();
            Session session=new Session(handle,revision);
            Session previous=sessions.putIfAbsent(id,session);
            if (previous!=null) {
                exchange(request(metadata,4,handle)).finish();
                throw rejected("Session identity already has a Java owner");
            }
            return session;
        }
    }

    private static void withLocks(List<Session> sessions,int index,Runnable action) {
        if(index==sessions.size()){action.run();return;}
        synchronized(sessions.get(index)){withLocks(sessions,index+1,action);}
    }
    @Override public void repartition(Repartition request) {
        synchronized(lifecycle) {
            var owners=request.sources().stream().map(r->session(r.session())).sorted(Comparator.comparingLong(s->s.handle)).toList();
            if(new HashSet<>(owners).size()!=owners.size())throw rejected("Duplicate repartition source");
            withLocks(owners,0,()->{
                for(var revision:request.sources()) {
                    var s=session(revision.session());requireOpen(s);
                    if(!s.revision.equals(revision))throw rejected("Repartition source changed");
                }
                double clock=owners.stream().mapToDouble(s->s.time).max().orElseThrow();
                var ids=new HashSet<UUID>();
                for(var target:request.targets())if(!ids.add(target.revision().session()) || sessions.containsKey(target.revision().session()))throw rejected("Destination already owned");
                var w=request(metadata,21,0).integer(request.sources().size());
                for(var revision:request.sources())w.longInteger(session(revision.session()).handle).revision(revision);
                w.integer(request.targets().size());
                for(var target:request.targets()){w.revision(target.revision()).integer(target.branches().size());target.branches().forEach(w::uuid);}
                var response=exchange(w);int count=response.count(64);
                if(count!=request.targets().size())throw rejected("Repartition destination count mismatch");
                var next=new ArrayList<Session>();
                for(var target:request.targets()){var s=new Session(response.longInteger(),target.revision());s.time=clock;next.add(s);}
                response.finish();
                for(var owner:owners){owner.closing.set(true);sessions.remove(owner.revision.session(),owner);}
                for(var owner:next)sessions.put(owner.revision.session(),owner);
            });
        }
    }

    @Override public void applyTopology(Topology topology) {applyBoundaryEdit(topology,false);}
    @Override public void applyBoundary(Topology topology) {applyBoundaryEdit(topology,true);}
    private void applyBoundaryEdit(Topology topology,boolean parameters) {
        var s=session(topology.revision().session());
        synchronized(s) {
            requireOpen(s);
            commitInput(s,encodeTopology(s,topology,parameters,false),topology.revision());
        }
    }
    private Writer encodeTopology(Session s,Topology topology,boolean parameters,boolean tick) {
        var compiled=compile(topology);
        int operation=parameters ? (topology.removalDisposition()==RemovalDisposition.EXPORT_STORED_ENERGY ? 17 : 16)
                : (topology.removalDisposition()==RemovalDisposition.EXPORT_STORED_ENERGY ? 14 : 11);
        Writer w=request(metadata,operation,s.handle).revision(topology.revision());
        if(!tick)w.number(s.time);
        w.integer(compiled.size());
        compiled.forEach(b -> branch(w,b));
        return w;
    }

    @Override public void setInterval(IntervalInputs inputs) {
        Session s=session(inputs.revision().session());
        synchronized(s) {
            requireOpen(s);
            commitInput(s,encodeInterval(s,inputs,false),inputs.revision());
        }
    }
    private Writer encodeInterval(Session s,IntervalInputs inputs,boolean tick) {
        Writer w=request(metadata,20,s.handle).revision(inputs.revision());
        if(!tick)w.number(inputs.start()).number(inputs.end());
        w.integer(inputs.inputs().size());
        for(var value:inputs.inputs()) {
            w.uuid(value.branch()).uuid(value.owner().instance()).longInteger(value.owner().generation());
            if(value.radiansPerSecond()!=null)w.integer(1).number(value.radiansPerSecond());
            else {w.integer(0);if(!tick)w.number(value.audioStart());for(double sample:value.samples())w.number(sample);}
        }
        return w;
    }

    @Override public void setParameters(Parameters parameters) {
        Session s=session(parameters.revision().session());
        synchronized(s) {
            requireOpen(s);
            commitInput(s,encodeParameters(s,parameters,false),parameters.revision());
        }
    }
    private Writer encodeParameters(Session s,Parameters parameters,boolean tick) {
        var changes=new ArrayList<>(parameters.branches());
        for (DeviceInput device:parameters.devices()) {
            Branch branch=deviceBranch(device);
            changes.add(new BranchParameters(branch.id(),branch.model(),branch.parameters()));
        }
        if (changes.size()>MAX_BRANCHES) throw rejected("Parameter target limit exceeded");
        Writer w=request(metadata,10,s.handle).revision(parameters.revision());
        if(!tick)w.number(s.time);
        w.integer(changes.size());
        changes.forEach(p -> parameters(w,p.id(),p.model(),p.parameters(),true));
        return w;
    }

    private static void commitInput(Session s,Writer writer,Revision revision) {
        exchange(writer).finish();
        s.revision=revision;
    }
    @Override public Observations advanceTick(TickRequest tick) {
        while (true) {
            var result=pollTick(tick);
            if(result.isPresent())return result.get();
        }
    }

    @Override public Optional<Observations> pollTick(TickRequest tick) {
        Revision revision=tick.revision();
        Session s=session(revision.session());
        synchronized (s) {
            requireOpen(s);
            if(!s.revision.equals(tick.base()))throw rejected("Tick input transaction conflict");
            boolean resumedTick = s.inFlightTick != null;
            if(!resumedTick) {
                var inputs=new ArrayList<byte[]>();
                for(var edit:tick.edits()) {
                    Writer input=switch(edit) {
                        case Topology topology -> encodeTopology(s,topology,true,true);
                        case Parameters parameters -> encodeParameters(s,parameters,true);
                        case IntervalInputs interval -> encodeInterval(s,interval,true);
                        case WaveformSubscription subscription -> encodeSubscription(s,subscription);
                    };
                    inputs.add(input.bytes());
                }
                var request=request(metadata,22,s.handle).revision(revision).longInteger(tick.worldTick()).number(tick.offset())
                    .number(tick.seconds()).integer(inputs.size());
                inputs.forEach(request::blob);
                s.inFlightBytes=request;
                s.inFlightTick=tick;
            } else if(!s.inFlightTick.equals(tick))throw rejected("Different input submitted during a tick continuation");
            Reader reader;
            try { reader=exchange(s.inFlightBytes, resumedTick); }
            catch(RuntimeException failure) {
                s.inFlightBytes=null;
                s.inFlightTick=null;
                throw failure;
            }
            int outcome=reader.count(3);
            if(outcome==0) {
                reader.finish();
                return Optional.empty();
            }
            s.inFlightBytes=null;
            s.inFlightTick=null;
            if(outcome!=1) {
                String reason=reader.string();
                reader.finish();
                throw new SimulationFailure(new Diagnostic(outcome==2?Status.REJECTED:Status.PARTIAL,reason));
            }
            if(!reader.revision().equals(revision))throw rejected("Native tick revision mismatch");
            double start=tick.worldTick()*0.05+tick.offset();
            var result=accepted(reader,revision,start,start+reader.number(),start+tick.seconds());
            var observed=observations(reader,result);
            s.revision=revision;
            s.time=result.end();
            return Optional.of(observed);
        }
    }

    @Override public Accepted advanceTo(Advance advance) {
        Session s=session(advance.revision().session());
        synchronized (s) {
            requireOpen(s);
            Reader r=exchange(request(metadata,2,s.handle).revision(advance.revision())
                    .number(advance.acceptedStart()).number(advance.requestedEnd())
                    .integer(advance.maxSteps()).longInteger(advance.budgetNanos()));
            Accepted accepted=accepted(r);
            if (!accepted.revision().equals(advance.revision()) || accepted.start()!=advance.acceptedStart()
                    || accepted.requestedEnd()!=advance.requestedEnd()) throw rejected("Native accepted request mismatch");
            state(r,accepted.end());
            s.revision=accepted.revision(); s.time=accepted.end();
            return accepted;
        }
    }

    @Override public void acknowledgeEvents(Revision revision,List<UUID> eventIds) {
        acknowledgeDestruction(revision,eventIds,List.of());
    }
    @Override public boolean exchangeEnergy(Revision revision,UUID branch,double joules,double maximumJoules,boolean commit) {
        Session s=session(revision.session());synchronized(s) {
            requireOpen(s);
            var reader=exchange(request(metadata,19,s.handle).revision(revision).uuid(branch).number(joules).number(maximumJoules).integer(commit?1:0));
            int result=reader.integer();reader.finish();
            if(result!=0 && result!=1)throw rejected("Invalid external energy exchange result");
            if(result==1 && commit)s.revision=revision;return result==1;
        }
    }
    @Override public void acknowledgeDestruction(Revision revision,List<UUID> eventIds,List<UUID> destroyedBranches) {
        var ids=List.copyOf(eventIds);
        var destroyed=List.copyOf(destroyedBranches);
        if (ids.isEmpty() || ids.size()>MAX_BRANCHES) throw rejected("Invalid event acknowledgement count");
        if(destroyed.size()>MAX_BRANCHES)throw rejected("Too many destroyed physical branches");
        Session s=session(revision.session());
        synchronized(s) {
            requireOpen(s);
            Writer w=request(metadata,18,s.handle).revision(revision).integer(ids.size());
            ids.forEach(w::uuid);w.integer(destroyed.size());destroyed.forEach(w::uuid);
            exchange(w).finish();s.revision=revision;
        }
    }

    @Override public Observations readObservations(Revision revision) {
        Session s=session(revision.session());
        synchronized (s) {
            requireOpen(s);
            Observations observations=observations(exchange(request(metadata,12,s.handle).revision(revision)));
            if (!observations.accepted().revision().equals(revision) || observations.accepted().end()!=s.time)
                throw rejected("Native observation revision/time mismatch");
            return observations;
        }
    }

    @Override public void subscribeWaveforms(WaveformSubscription subscription) {
        Session s=session(subscription.revision().session());
        synchronized (s) {
            requireOpen(s);
            commitInput(s,encodeSubscription(s,subscription),subscription.revision());
        }
    }
    private Writer encodeSubscription(Session s,WaveformSubscription subscription) {
        Writer w=request(metadata,13,s.handle).revision(subscription.revision())
                .number(subscription.samplesPerSecond()).number(subscription.bandwidthHertz())
                .integer(subscription.maxSamples()).integer(subscription.channels().size());
        for (WaveformChannel channel:subscription.channels()) w.uuid(channel.channel()).port(channel.positive()).port(channel.negative());
        return w;
    }

    @Override public Snapshot snapshot(Revision revision) {
        Session s=session(revision.session());
        synchronized (s) {
            requireOpen(s);
            if (!revision.equals(s.revision)) throw rejected("Snapshot revision conflict");
            Reader r=exchange(request(metadata,6,s.handle));
            byte[] payload=r.blob(); r.finish();
            return new Snapshot(revision,metadata,s.time,payload);
        }
    }

    /** Read the domain's accepted physical time from the native checkpoint. */
    public static double snapshotClock(byte[] payload,String metadata,Revision revision) {
        Reader envelope=new Reader(payload);
        envelope.count(1);
        Reader inner=new Reader(envelope.blob());
        envelope.finish();
        if(inner.integer()!=0x50534d45 || !inner.string().equals(metadata) || !inner.revision().equals(revision))
            throw rejected("Snapshot envelope revision mismatch");
        inner.number();
        inner.number();
        if(inner.count(1)==1)for(int i=0;i<7;i++)inner.number();
        return inner.number();
    }

    @Override public void restore(UUID id, Snapshot snapshot) {
        requireAvailable();
        if (!snapshot.modVersion().equals(metadata) || !snapshot.revision().session().equals(id))
            throw rejected("Snapshot mod metadata/session mismatch");
        if(snapshotClock(snapshot.payload(),metadata,snapshot.revision())!=snapshot.acceptedTime())
            throw rejected("Snapshot envelope accepted time mismatch");
        synchronized (lifecycle) {
            requireAvailable();
            boolean created=!sessions.containsKey(id);
            Session s=created ? create(id) : session(id);
            try {
                synchronized (s) {
                    requireOpen(s);
                    exchange(request(metadata,7,s.handle).blob(snapshot.payload())).finish();
                    s.revision=snapshot.revision(); s.time=snapshot.acceptedTime();
                }
            } catch (RuntimeException failure) {
                if (created) destroy(id);
                throw failure;
            }
        }
    }

    @Override public void destroy(UUID id) {
        Session s=sessions.get(id);
        if (s==null || !s.closing.compareAndSet(false,true)) return;
        // Cancellation may run while advancement holds the Java monitor. Destruction waits for
        // active native operations before releasing the handle.
        try { exchange(request(metadata,5,s.handle)).finish(); }
        finally {
            synchronized (s) {
                try { exchange(request(metadata,4,s.handle)).finish(); }
                finally { sessions.remove(id,s); }
            }
        }
    }

    @Override public void close() {
        synchronized (lifecycle) {
            if (!closed.compareAndSet(false,true)) return;
        }
        RuntimeException first=null;
        for (UUID id:List.copyOf(sessions.keySet())) {
            try { destroy(id); } catch (RuntimeException failure) {
                if (first==null) first=failure; else first.addSuppressed(failure);
            }
        }
        if (first!=null) throw first;
    }
}
