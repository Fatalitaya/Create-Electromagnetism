package com.electromagnetism.simulation;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import static com.electromagnetism.simulation.SimulationProtocol.*;

/** One owner and at most one native task per dimension. World objects never enter
 * the worker. All result validation and world effects occur on the creating thread. */
public final class SimulationCoordinator implements AutoCloseable {
    public record Frame(List<DeviceInput> devices,List<Branch> branches,List<String> blockers,
                        RemovalDisposition removalDisposition,List<WaveformChannel> waveforms) {
        public Frame(List<DeviceInput> devices,List<Branch> branches,List<String> blockers,RemovalDisposition removalDisposition) {
            this(devices,branches,blockers,removalDisposition,List.of());
        }
        public Frame {
            devices=List.copyOf(devices);branches=List.copyOf(branches);blockers=List.copyOf(blockers);
            waveforms=List.copyOf(waveforms);
            Objects.requireNonNull(removalDisposition);
            if (devices.size()>4096 || branches.size()>4096 || blockers.size()>4096 || waveforms.size()>32)
                throw new IllegalArgumentException("World capture resource limit exceeded");
            if(devices.stream().anyMatch(d->d.ports().size()>4096 || d.parameters().size()>4096)
                    || branches.stream().anyMatch(b->b.additionalPorts().size()>4096 || b.parameters().size()>4096))
                throw new IllegalArgumentException("World input field count exceeds history encoding limit");
        }
    }
    private record Completion(Revision revision,double time,Frame installed,Observations observations,Diagnostic diagnostic,boolean acknowledged) {}
    private final Thread owner=Thread.currentThread();
    private final SimulationBackend backend;
    private final Executor executor;
    private final int maxSteps;
    private final long budgetNanos;
    private Revision revision;
    private double acceptedTime,gameTime;
    private final WorldInputHistory history=new WorldInputHistory();
    private Frame installed,current;
    private CompletableFuture<Completion> pending;
    private List<UUID> acknowledgements=List.of();
    private List<UUID> destroyedBranches=List.of();
    private Set<DeviceId> destroyedDevices=Set.of();
    private boolean applying;
    private Diagnostic diagnostic;
    private boolean closed;

    public SimulationCoordinator(SimulationBackend backend,Executor executor,int maxSteps,long budgetNanos) {
        this(backend,executor,maxSteps,budgetNanos,null);
    }
    public SimulationCoordinator(SimulationBackend backend,Executor executor,int maxSteps,long budgetNanos,Snapshot restored) {
        this(backend,executor,maxSteps,budgetNanos,restored,null);
    }
    public SimulationCoordinator(SimulationBackend backend,Executor executor,int maxSteps,long budgetNanos,Snapshot restored,byte[] inputHistory) {
        this.backend=Objects.requireNonNull(backend);this.executor=Objects.requireNonNull(executor);
        if (maxSteps<=0 || budgetNanos<=0) throw new IllegalArgumentException("Work must be bounded");
        this.maxSteps=maxSteps;this.budgetNanos=budgetNanos;diagnostic=backend.availability();
        if (diagnostic.status()==Status.READY) {
            if (restored==null) revision=new Revision(backend.create(),0,0,0);
            else {
                acceptedTime=restored.acceptedTime();history.target=acceptedTime;
                if(inputHistory!=null)installed=history.restore(inputHistory,acceptedTime);
                if(history.awaitingEffects!=null && !history.awaitingEffects.accepted().revision().equals(restored.revision()))
                    throw new IllegalArgumentException("Pending effects revision differs from native checkpoint");
                backend.restore(restored.revision().session(),restored);revision=restored.revision();gameTime=history.target;
            }
        }
    }
    private void owner() {
        if (Thread.currentThread()!=owner) throw new IllegalStateException("World boundary called off owner thread");
        if (closed) throw new IllegalStateException("Closed world coordinator");
    }
    public boolean exchangeEnergy(Frame live,UUID branch,double joules,double maximumJoules,boolean commit) {
        owner();
        if(pending!=null || applying || installed==null || !live.equals(installed) || !history.pause.isEmpty()
                || !history.entries.isEmpty() || history.awaitingEffects!=null || !acknowledgements.isEmpty()
                || Math.abs(uncompletedSeconds())>1e-12 || diagnostic.status()!=Status.READY)return false;
        var next=new Revision(revision.session(),revision.topology(),revision.parameters(),commit?Math.incrementExact(revision.sequence()):revision.sequence());
        try {
            boolean result=backend.exchangeEnergy(next,branch,joules,maximumJoules,commit);
            if(result && commit)revision=next;
            return result;
        } catch(SimulationFailure failed) {
            diagnostic=failed.diagnostic();
            return false;
        }
    }
    public Diagnostic status(){
        owner();
        if(!history.pause.isEmpty())return new Diagnostic(history.pause.startsWith("Unsupported world input: ")?Status.UNSUPPORTED:Status.PARTIAL,"Paused: "+history.pause);
        if(diagnostic.status()==Status.READY && uncompletedSeconds()>1e-12)
            return new Diagnostic(Status.PARTIAL,"Historical world inputs await acceptance");
        return diagnostic;
    }
    public record Progress(double acceptedTime,double targetTime,double backlogSeconds,double excludedSeconds,int queuedBoundaries,int encodedBytes,
                           String pauseReason,double missingStart,double missingEnd,List<UUID> pendingEvents) {}
    public Progress progress() {
        owner();return new Progress(acceptedTime,gameTime-history.excludedSeconds,uncompletedSeconds(),history.excludedSeconds,history.entries.size(),history.bytes,
                history.pause,history.missingStart,history.missingEnd,history.awaitingEffects==null?acknowledgements:
                        history.awaitingEffects.events().stream().map(ProtectionEvent::id).toList());
    }
    public double acceptedTime(){owner();return acceptedTime;}
    /** Game time not yet physically accepted; never concealed by wall-clock catch-up. */
    public double uncompletedSeconds(){owner();return Math.max(0,gameTime-history.excludedSeconds-acceptedTime);}
    public boolean hasPendingTask(){owner();return pending!=null;}
    public Optional<Branch> acceptedBranch(UUID branch) {
        owner();return installed==null?Optional.empty():installed.branches().stream().filter(b->b.id().equals(branch)).findFirst();
    }
    public boolean canExchangeEnergy() {
        owner();return revision!=null && pending==null && !applying && installed!=null
                && history.pause.isEmpty() && history.entries.isEmpty() && history.awaitingEffects==null
                && acknowledgements.isEmpty() && uncompletedSeconds()<=1e-12 && diagnostic.status()==Status.READY;
    }

    /** Dispatch retained work without inventing a game tick or waiting for a worker. */
    public void pump(Frame live) {owner();current=Objects.requireNonNull(live);if(revision!=null)submitHead();}

    public void tick(Frame frozen) {
        owner();current=Objects.requireNonNull(frozen);
        if (revision==null) return;
        history.append(frozen);gameTime=history.target;
        submitHead();
    }

    private void submitHead() {
        if(!history.pause.isEmpty())return;
        if (pending!=null || history.entries.isEmpty()) return;
        var head=history.entries.getFirst();
        if (!head.frame().blockers().isEmpty()) {
            diagnostic=new Diagnostic(Status.UNSUPPORTED,String.join("; ",head.frame().blockers()));return;
        }
        if(!containsBindings(current,head.frame())) {
            history.pause="Historical input references an instance no longer present in the world";return;
        }
        // Retain every captured boundary in history, but advance identical passive
        // inputs together. Audio, probes and mechanical consumers retain tick windows.
        double target=head.end();
        if(head.frame().waveforms().isEmpty() && head.frame().branches().stream().noneMatch(b->
                Set.of("audio_source","radio_transmitter","carrier_mixer","shaft_boundary","ac_shaft","field_shaft","field_ac_shaft").contains(b.model()))) {
            int count=0;
            for(var entry:history.entries) {
                if(++count>4 || !entry.frame().equals(head.frame()))break;
                target=entry.end();
            }
        }
        final double requestedEnd=target;
        Revision start=revision;double time=acceptedTime;Frame previous=installed;
        List<UUID> ack=acknowledgements;
        List<UUID> destroyed=destroyedBranches;
        try {pending=CompletableFuture.supplyAsync(()->execute(start,time,requestedEnd,previous,head.frame(),ack,destroyed),executor);}
        catch (RejectedExecutionException full) {diagnostic=new Diagnostic(Status.PARTIAL,"Native task executor is full");}
    }

    private Completion execute(Revision start,double time,double target,Frame previous,Frame frozen,List<UUID> ack,List<UUID> destroyed) {
        Revision cursor=start;Frame applied=previous;boolean acknowledged=false;
        try {
            if (previous==null && cursor.sequence()>0) {
                var probe=new Revision(cursor.session(),cursor.topology(),cursor.parameters(),Math.addExact(cursor.sequence(),1));
                backend.advanceTo(new Advance(probe,time,time,maxSteps,budgetNanos));cursor=probe;
                var events=backend.readObservations(cursor);
                if (!events.events().isEmpty()) return new Completion(cursor,time,frozen,events,
                        new Diagnostic(Status.PARTIAL,"Restored protection boundary awaits world confirmation"),false);
            }
            if (!ack.isEmpty()) {
                var next=new Revision(cursor.session(),Math.addExact(cursor.topology(),1),cursor.parameters(),Math.addExact(cursor.sequence(),1));
                backend.acknowledgeDestruction(next,ack,destroyed);cursor=next;acknowledged=true;
            }
            if (previous==null || !sameParameters(frozen,previous) || !sameBindings(frozen,previous) || !frozen.waveforms().equals(previous.waveforms()) || acknowledged) {
                boolean audio=frozen.branches().stream().anyMatch(b->b.model().equals("radio_transmitter")||b.model().equals("carrier_mixer")||b.model().equals("audio_source"));
                boolean previousAudio=previous!=null && previous.branches().stream().anyMatch(b->b.model().equals("radio_transmitter")||b.model().equals("carrier_mixer")||b.model().equals("audio_source"));
                boolean rebind=previous==null || acknowledged || audio!=previousAudio || !previous.waveforms().equals(frozen.waveforms());
                // Unsubscribe before deleting probe terminals. New bindings get
                // fresh identities; an unchanged restored binding keeps its sampler history.
                if(rebind && (previous==null || !previous.waveforms().isEmpty())) {
                    var unsubscribe=new Revision(cursor.session(),cursor.topology(),cursor.parameters(),Math.addExact(cursor.sequence(),1));
                    backend.subscribeWaveforms(new WaveformSubscription(unsubscribe,List.of(),4096,256,256));cursor=unsubscribe;
                }
                boolean topologyChanged=previous==null || acknowledged || !sameBindings(frozen,previous);
                var next=new Revision(cursor.session(),topologyChanged?Math.addExact(cursor.topology(),1):cursor.topology(),Math.addExact(cursor.parameters(),1),Math.addExact(cursor.sequence(),1));
                if(topologyChanged)backend.applyBoundary(new Topology(next,frozen.devices(),frozen.branches(),frozen.removalDisposition()));
                else backend.setParameters(new Parameters(next,frozen.devices().stream().filter(d->!d.model().equals("ports")).toList(),frozen.branches().stream().map(b->new BranchParameters(b.id(),b.model(),b.parameters())).toList()));
                cursor=next;
                if(rebind && !frozen.waveforms().isEmpty()) {
                    var channels=frozen.waveforms().stream().map(c->new WaveformChannel(UUID.randomUUID(),c.positive(),c.negative())).toList();
                    var subscription=new Revision(cursor.session(),cursor.topology(),cursor.parameters(),Math.addExact(cursor.sequence(),1));
                    // Every request spans at most one 50 ms input boundary: 800
                    // audio samples per channel, independent of other probes.
                    backend.subscribeWaveforms(new WaveformSubscription(subscription,channels,audio?16000:4096,audio?2000:256,audio?1024:256));cursor=subscription;
                }
                applied=frozen;
            }
            if(previous!=frozen && time <= target-0.05+1e-12) {
                var inputs=intervalValues(frozen);
                if(!inputs.isEmpty()) {
                    var inputRevision=new Revision(cursor.session(),cursor.topology(),cursor.parameters(),Math.incrementExact(cursor.sequence()));
                    backend.setInterval(new IntervalInputs(inputRevision,time,target,inputs));cursor=inputRevision;
                }
                applied=frozen;
            }
            var next=new Revision(cursor.session(),cursor.topology(),cursor.parameters(),Math.addExact(cursor.sequence(),1));
            Accepted accepted=backend.advanceTo(new Advance(next,time,target,maxSteps,budgetNanos));
            cursor=accepted.revision();time=accepted.end();
            Observations observations=backend.readObservations(cursor);
            return new Completion(cursor,time,applied,observations,accepted.diagnostic(),acknowledged);
        } catch (SimulationFailure failed) {
            return new Completion(cursor,time,applied,null,failed.diagnostic(),acknowledged);
        } catch (IllegalArgumentException | ArithmeticException failed) {
            return new Completion(cursor,time,applied,null,new Diagnostic(Status.REJECTED,failed.getMessage()),acknowledged);
        }
    }

    /** Re-freeze world facts before invoking this boundary. The sink returns only
     * event identities whose world effects have actually been applied. */
    public void endTick(Frame live,Function<Observations,List<UUID>> sink) {
        owner();current=Objects.requireNonNull(live);Objects.requireNonNull(sink);
        if (pending==null || !pending.isDone()) return;
        Completion completed;
        try {completed=pending.join();}
        catch (CompletionException failed) {
            history.pause="Native worker failed before a verifiable completion";
            diagnostic=new Diagnostic(Status.REJECTED,"Native worker failed: "+failed.getCause());pending=null;return;
        }
        double previousTime=acceptedTime;
        pending=null;revision=completed.revision();acceptedTime=completed.time();installed=completed.installed();
        history.consume(acceptedTime);
        diagnostic=completed.diagnostic();if (completed.acknowledged()) {acknowledgements=List.of();destroyedBranches=List.of();destroyedDevices=Set.of();}
        if (completed.observations()==null) {
            if(acceptedTime>previousTime)history.pause="Accepted native interval has no verifiable world effects";
            return;
        }
        history.awaitingEffects=completed.observations();
        if (!live.blockers().isEmpty() || !containsBindings(live,installed)
                || (!live.equals(installed) && completed.observations().readings().stream().anyMatch(r->r.torqueNewtonMetres().isPresent()))) {
            history.pause="Accepted world effects await matching instance and boundary; automatic continuation is unsafe";
            diagnostic=new Diagnostic(Status.PARTIAL,history.pause);return;
        }
        var result=completed.observations();
        if (!result.accepted().revision().equals(revision) || result.accepted().end()!=acceptedTime) {
            history.pause="Native completion does not match the accepted world boundary";
            throw new IllegalStateException("Native completion does not match owner boundary");
        }
        List<UUID> confirmed;
        applying=true;
        try {confirmed=List.copyOf(sink.apply(result));}
        catch(RuntimeException | Error failure) {history.pause="World effect application failed; confirmation required";throw failure;}
        finally {applying=false;}
        var expected=result.events().stream().map(ProtectionEvent::id).toList();
        if (!confirmed.isEmpty() && !confirmed.equals(expected)) {
            history.pause="World confirmed an incomplete event boundary";
            throw new IllegalArgumentException("World must confirm the complete native event boundary in order");
        }
        acknowledgements=confirmed;
        if(!expected.isEmpty() && confirmed.isEmpty())history.pause="Protection events await world confirmation";
        else {
            if(!confirmed.isEmpty()) {
                var destroyed=new HashSet<>(destroyedBranches);
                history.removeDestroyed(destroyedDevices,destroyed);
                installed=WorldInputHistory.withoutDestroyed(installed,destroyedDevices,destroyed);
            }
            history.awaitingEffects=null;
        }
    }

    private static Map<String,Double> staticParameters(String model,Map<String,Double> values) {
        var parameters=new HashMap<>(values);
        if(Set.of("audio_source","radio_transmitter").contains(model)) {
            parameters.remove("startSeconds");for(int i=0;i<800;i++)parameters.remove("sample"+i);
        }
        if(Set.of("shaft_boundary","ac_shaft","field_shaft","field_ac_shaft").contains(model))parameters.remove("radiansPerSecond");
        return parameters;
    }
    private static boolean sameParameters(Frame a,Frame b) {
        if(!sameBindings(a,b))return false;
        return a.devices().stream().map(d->List.of(d.id(),staticParameters(d.model(),d.parameters()))).collect(java.util.stream.Collectors.toSet())
            .equals(b.devices().stream().map(d->List.of(d.id(),staticParameters(d.model(),d.parameters()))).collect(java.util.stream.Collectors.toSet()))
            && a.branches().stream().map(d->List.of(d.id(),staticParameters(d.model(),d.parameters()))).collect(java.util.stream.Collectors.toSet())
            .equals(b.branches().stream().map(d->List.of(d.id(),staticParameters(d.model(),d.parameters()))).collect(java.util.stream.Collectors.toSet()));
    }
    private static List<IntervalValue> intervalValues(Frame frame) {
        var result=new ArrayList<IntervalValue>();
        var branches=new ArrayList<>(frame.branches());
        for(var device:frame.devices())if(!device.model().equals("ports"))branches.add(NativeWireCodec.deviceBranch(device));
        for(var b:branches) {
            if(Set.of("audio_source","radio_transmitter").contains(b.model())) {
                double[] samples=new double[800];for(int i=0;i<800;i++)samples[i]=b.parameters().get("sample"+i);
                result.add(new IntervalValue(b.id(),b.positive().device(),samples,b.parameters().get("startSeconds"),null));
            } else if(Set.of("shaft_boundary","ac_shaft","field_shaft","field_ac_shaft").contains(b.model()))
                result.add(new IntervalValue(b.id(),b.positive().device(),null,0,b.parameters().get("radiansPerSecond")));
        }
        return result;
    }

    /** Stable world identities and electrical connections, excluding changing input values. */
    public static boolean sameBindings(Frame live,Frame historical) {
        if(historical==null || live==null)return false;
        return live.devices().size()==historical.devices().size()
                && live.devices().stream().map(d->List.of(d.id(),bindingModel(d.model()),d.ports())).collect(java.util.stream.Collectors.toSet())
                .equals(historical.devices().stream().map(d->List.of(d.id(),bindingModel(d.model()),d.ports())).collect(java.util.stream.Collectors.toSet()))
                && live.branches().size()==historical.branches().size()
                && live.branches().stream().map(b->List.of(b.id(),b.positive(),b.negative(),bindingModel(b.model()),b.additionalPorts())).collect(java.util.stream.Collectors.toSet())
                .equals(historical.branches().stream().map(b->List.of(b.id(),b.positive(),b.negative(),bindingModel(b.model()),b.additionalPorts())).collect(java.util.stream.Collectors.toSet()));
    }

    /** New devices/connections do not invalidate already recorded input intervals.
     * Every historical owner, terminal and branch must still be present unchanged;
     * removals, rewiring and replacements continue to require explicit reconciliation.
     */
    private static boolean containsBindings(Frame live,Frame historical) {
        if(live==null || historical==null)return false;
        var devices=live.devices().stream().map(d->List.of(d.id(),bindingModel(d.model()),d.ports())).collect(java.util.stream.Collectors.toSet());
        var branches=live.branches().stream().map(b->List.of(b.id(),b.positive(),b.negative(),bindingModel(b.model()),b.additionalPorts())).collect(java.util.stream.Collectors.toSet());
        return historical.devices().stream().allMatch(d->devices.contains(List.of(d.id(),bindingModel(d.model()),d.ports())))
                && historical.branches().stream().allMatch(b->branches.contains(List.of(b.id(),b.positive(),b.negative(),bindingModel(b.model()),b.additionalPorts())));
    }

    private static String bindingModel(String model) {
        // Both names encode Rust's ThermalResistor. A trip threshold is a control
        // parameter, not a new world owner or a different electrical connection.
        return model.equals("thermal_fuse") ? "thermal_resistor" : model;
    }

    /** Explicitly close a resource-induced missing-input interval. Retained history is not discarded. */
    public void resumeMissingInputs() {
        owner();
        if(!history.pause.equals("World input history is full") && !history.pause.equals("World input exceeds encoding limit")
                && !history.pause.startsWith("Unsupported world input: "))
            throw new IllegalStateException("No recoverable missing-input interval to resume");
        if(current==null || !current.blockers().isEmpty())throw new IllegalStateException("Resolve unsupported world inputs before resuming");
        if(pending!=null)throw new IllegalStateException("Complete the in-flight task before resuming");
        history.resume();
        submitHead();
    }

    /** Must be saved together with checkpoint(), on the owner thread. */
    public byte[] inputHistoryCheckpoint() {owner();if(pending!=null)throw new IllegalStateException("Checkpoint native work first");return history.save(installed);}

    /** Only the main-thread event sink can identify additional branches physically destroyed by its effects. */
    public void confirmDestroyedBranches(List<UUID> branches) {
        owner();if(!applying)throw new IllegalStateException("Destruction must belong to the active event boundary");
        destroyedBranches=List.copyOf(branches);
    }

    /** The active sink supplies the world topology after applying the complete event boundary. */
    public void confirmDestroyedBoundary(List<UUID> branches,Frame live) {
        confirmDestroyedBranches(branches);
        var remaining=live.devices().stream().map(DeviceInput::id).collect(java.util.stream.Collectors.toSet());
        destroyedDevices=installed.devices().stream().map(DeviceInput::id).filter(id->!remaining.contains(id)).collect(java.util.stream.Collectors.toSet());
    }

    public Snapshot checkpoint(Frame live,Function<Observations,List<UUID>> sink) {
        owner();if (revision==null) throw new SimulationFailure(diagnostic.status(),diagnostic.reason());
        // Save is an explicit boundary transaction; normal endTick never waits.
        if (pending!=null) {pending.join();endTick(live,sink);}
        if (!acknowledgements.isEmpty()) {
            Revision next=new Revision(revision.session(),Math.addExact(revision.topology(),1),revision.parameters(),Math.addExact(revision.sequence(),1));
            backend.acknowledgeDestruction(next,acknowledgements,destroyedBranches);revision=next;acknowledgements=List.of();destroyedBranches=List.of();destroyedDevices=Set.of();
        }
        return backend.snapshot(revision);
    }

    @Override public void close() {
        owner();closed=true;
        if (revision!=null) backend.destroy(revision.session());
        pending=null;
    }
}
