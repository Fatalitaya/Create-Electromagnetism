package com.electromagnetism.simulation;

import java.util.*;
import java.util.concurrent.Executor;
import static com.electromagnetism.simulation.SimulationProtocol.*;

/** Scheduling checks use a deterministic backend; JNI checks separately verify physical results. */
public final class WorldInputHistoryChecks {
    private static final Diagnostic READY=new Diagnostic(Status.READY,"");
    public static void main(String[] args) {
        var id=new DeviceId(UUID.randomUUID(),0);var ports=List.of(new PortId(id,0),new PortId(id,1));
        var first=new SimulationCoordinator.Frame(List.of(new DeviceInput(id,"resistor",ports,Map.of("resistanceOhms",2.0))),List.of(),List.of(),RemovalDisposition.REJECT_ENERGIZED);
        var changed=new SimulationCoordinator.Frame(List.of(new DeviceInput(id,"resistor",ports,Map.of("resistanceOhms",4.0))),List.of(),List.of(),RemovalDisposition.REJECT_ENERGIZED);
        var backend=new Backend();var worker=new Worker();Snapshot saved;byte[] history;
        try(var coordinator=new SimulationCoordinator(backend,worker,1,1000)) {
            coordinator.tick(first);coordinator.tick(changed);worker.run();
            coordinator.endTick(changed,o->List.of());
            near(coordinator.acceptedTime(),0.025);require(coordinator.progress().queuedBoundaries()==2,"Partial consumed a whole input");
            coordinator.tick(changed);worker.run();coordinator.endTick(changed,o->List.of());
            near(backend.requests.get(1).requestedEnd(),0.05);
            require(backend.values.equals(List.of(2.0,2.0)),"Partial remainder used a newer command");
            require(coordinator.status().status()==Status.PARTIAL,"Backlog falsely READY");
            saved=coordinator.checkpoint(changed,o->{throw new AssertionError("Checkpoint replay");});history=coordinator.inputHistoryCheckpoint();
        }
        var restoredBackend=new Backend();var restoredWorker=new Worker();
        try(var restored=new SimulationCoordinator(restoredBackend,restoredWorker,1,1000,saved,history)) {
            near(restored.uncompletedSeconds(),0.1);require(restored.progress().queuedBoundaries()==2,"Restore lost history");
            restored.tick(changed);restoredWorker.run();restored.endTick(changed,o->List.of());
            require(restoredBackend.values.equals(List.of(4.0)),"Restore lost historical command");
            // Identical passive boundaries are batched, including the new tick.
            near(restoredBackend.requests.getFirst().requestedEnd(),0.2);
        }
        var queue=new WorldInputHistory();for(int n=0;n<256;n++)require(queue.append(first),"Premature full queue");
        require(!queue.append(changed) && queue.entries.size()==256,"Full queue evicted history");
        double missing=queue.missingStart;queue.append(first);require(queue.missingStart==missing && queue.missingEnd==queue.target,"Missing interval lost");
        var copy=new WorldInputHistory();require(copy.restore(queue.save(first),0).equals(first),"Installed frame lost");
        require(copy.pause.equals(queue.pause),"Queue restore failed");
        var originalEntries=new ArrayList<>(queue.entries);var restoredEntries=new ArrayList<>(copy.entries);
        require(originalEntries.size()==restoredEntries.size(),"Queue size changed");
        for(int n=0;n<originalEntries.size();n++) {
            var a=originalEntries.get(n);var b=restoredEntries.get(n);
            require(a.end()==b.end() && a.frame().equals(b.frame()) && Arrays.equals(a.encoded(),b.encoded()),"Queue ordering changed");
        }
        copy.resume();require(copy.pause.isEmpty() && copy.entries.size()==256,"Resume discarded retained inputs");
        byte[] malformed=Arrays.copyOf(history,history.length-1);
        try {new WorldInputHistory().restore(malformed,0.05);throw new AssertionError("Truncated history accepted");}
        catch(SimulationFailure expected) { }
        var oversized=new SimulationCoordinator.Frame(List.of(new DeviceInput(id,"x".repeat(1024*1024),ports,Map.of())),List.of(),List.of(),RemovalDisposition.REJECT_ENERGIZED);
        var byteLimited=new WorldInputHistory();int accepted=0;while(byteLimited.append(oversized))accepted++;
        require(accepted<256 && byteLimited.bytes<=WorldInputHistory.MAX_BYTES,"Encoded byte bound ignored");
        var fullBackend=new Backend();var fullWorker=new Worker();
        try(var coordinator=new SimulationCoordinator(fullBackend,fullWorker,1,1000)) {
            for(int n=0;n<257;n++)coordinator.tick(first);
            require(coordinator.progress().queuedBoundaries()==256 && !coordinator.progress().pauseReason().isEmpty(),"Coordinator did not apply queue backpressure");
            fullWorker.run();coordinator.endTick(first,o->List.of());
            double missingDuration=coordinator.progress().missingEnd()-coordinator.progress().missingStart();
            near(missingDuration,0.05);coordinator.resumeMissingInputs();
            for(int attempts=0;attempts<1024 && coordinator.progress().queuedBoundaries()>0;attempts++) {
                if(!coordinator.hasPendingTask())coordinator.pump(first);
                fullWorker.run();coordinator.endTick(first,o->List.of());
            }
            near(coordinator.acceptedTime(),12.8);near(coordinator.uncompletedSeconds(),0);
            near(coordinator.progress().excludedSeconds(),0.05);
            require(coordinator.status().status()==Status.READY,"Explicit resume failed to drain retained history");
        }
        var failedRead=new Backend();failedRead.failRead=true;var failedWorker=new Worker();
        try(var coordinator=new SimulationCoordinator(failedRead,failedWorker,1,1000)) {
            coordinator.tick(first);failedWorker.run();coordinator.endTick(first,o->{throw new AssertionError("Unverified effects applied");});
            near(coordinator.acceptedTime(),0.025);
            require(!coordinator.progress().pauseReason().isEmpty(),"Accepted interval with missing effects did not pause");
            coordinator.tick(first);require(!coordinator.hasPendingTask(),"Missing effects were silently skipped");
        }
        var audioBackend=new Backend();var audioWorker=new Worker();
        var channels=java.util.stream.IntStream.range(0,32).mapToObj(n->new WaveformChannel(UUID.randomUUID(),ports.get(0),ports.get(1))).toList();
        var audio=new SimulationCoordinator.Frame(first.devices(),List.of(new Branch(UUID.randomUUID(),ports.get(0),ports.get(1),"audio_source",Map.of())),List.of(),RemovalDisposition.REJECT_ENERGIZED,channels);
        try(var coordinator=new SimulationCoordinator(audioBackend,audioWorker,1,1000)) {
            coordinator.tick(audio);audioWorker.run();coordinator.endTick(audio,o->List.of());
            require(audioBackend.subscription.channels().size()==32 && audioBackend.subscription.samplesPerSecond()==16000
                    && audioBackend.subscription.maxSamples()>=801,"Audio subscription cannot retain a complete tick");
        }
        System.out.println("World input history passed: command order, partial remainder, durable backlog, full queue and byte bounds");
    }
    private static void near(double actual,double expected) {require(Math.abs(actual-expected)<1e-12,"Time mismatch: "+actual+" != "+expected);}
    private static void require(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
    private static final class Worker implements Executor {
        Runnable task;
        public void execute(Runnable next) {if(task!=null)throw new AssertionError("Concurrent task");task=next;}
        void run() {var next=task;task=null;next.run();}
    }
    private static final class Backend implements SimulationBackend {
        final List<Advance> requests=new ArrayList<>();final List<Double> values=new ArrayList<>();
        Topology topology;Accepted accepted;double time;boolean failRead;WaveformSubscription subscription;
        public Diagnostic availability(){return READY;}
        public UUID create(){return UUID.randomUUID();}
        public void applyTopology(Topology value){topology=value;}
        public void applyBoundary(Topology value){topology=value;}
        public void setParameters(Parameters value){topology=new Topology(value.revision(),value.devices(),List.of(),RemovalDisposition.REJECT_ENERGIZED);}
        public Accepted advanceTo(Advance request) {
            requests.add(request);values.add(topology.devices().getFirst().parameters().get("resistanceOhms"));
            time=Math.min(request.requestedEnd(),request.acceptedStart()+0.025);
            return accepted=new Accepted(request.revision(),request.acceptedStart(),time,request.requestedEnd(),time==request.requestedEnd()?READY:new Diagnostic(Status.PARTIAL,"budget"));
        }
        public Observations readObservations(Revision revision){
            if(failRead)throw new SimulationFailure(Status.UNAVAILABLE,"Injected observation failure");
            return new Observations(accepted,List.of(),List.of());
        }
        public void acknowledgeEvents(Revision revision,List<UUID> events){throw new AssertionError();}
        public void subscribeWaveforms(WaveformSubscription subscription){this.subscription=subscription;}
        public Snapshot snapshot(Revision revision){return new Snapshot(revision,"test",time,new byte[0]);}
        public void restore(UUID session,Snapshot snapshot){time=snapshot.acceptedTime();}
        public void destroy(UUID session){}
    }
}
