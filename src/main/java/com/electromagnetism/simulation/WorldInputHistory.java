package com.electromagnetism.simulation;

import java.util.*;
import static com.electromagnetism.simulation.SimulationProtocol.*;

/** Owner-thread queue of captured inputs and pending accepted effects. */
final class WorldInputHistory {
    static final int MAX_BOUNDARIES=256, MAX_BYTES=16*1024*1024;
    static final int CHECKPOINT_BYTES=MAX_BYTES+5*1024*1024;
    record Entry(double end, SimulationCoordinator.Frame frame, byte[] encoded) {
        int bytes(){return 12+encoded.length;}
    }
    final ArrayDeque<Entry> entries=new ArrayDeque<>();
    int bytes;
    double target, missingStart=-1, missingEnd=-1, excludedSeconds;
    String pause="";
    Observations awaitingEffects;

    boolean append(SimulationCoordinator.Frame frame) {
        double start=target;target+=0.05;
        if (!pause.isEmpty()) { if(missingStart<0)missingStart=start;missingEnd=target;return false; }
        if(!frame.blockers().isEmpty()) {
            var reason=new StringBuilder("Unsupported world input: ");
            for(var blocker:frame.blockers()) {
                int available=4096-reason.length();if(available<=0)break;
                reason.append(blocker,0,Math.min(blocker.length(),available));if(reason.length()<4094)reason.append("; ");
            }
            pause=reason.toString();missingStart=start;missingEnd=target;return false;
        }
        final byte[] encoded;
        final int size;
        try {encoded=encodeFrame(frame);size=12+encoded.length;}
        catch (SimulationFailure oversized) {pause="World input exceeds encoding limit";missingStart=start;missingEnd=target;return false;}
        if(entries.size()==MAX_BOUNDARIES || size>MAX_BYTES-bytes) {
            pause="World input history is full";missingStart=start;missingEnd=target;return false;
        }
        entries.addLast(new Entry(target-excludedSeconds,frame,encoded));bytes+=size;return true;
    }
    void consume(double accepted) {
        while(!entries.isEmpty() && accepted>=entries.getFirst().end())bytes-=entries.removeFirst().bytes();
    }
    /** Expire unaccepted time without advancing native state or rewriting accepted effects. */
    void expireTick(double accepted) {
        if(awaitingEffects!=null || (!pause.isEmpty() && missingStart<0))
            throw new IllegalStateException("Unresolved accepted effects cannot expire with a tick");
        excludedSeconds=Math.max(excludedSeconds,target-accepted);
        entries.clear();bytes=0;
        missingStart=-1;missingEnd=-1;pause="";
    }
    /** Exclude the failed remainder while keeping later captured boundaries. */
    void expireHead(double accepted) {
        if(entries.isEmpty())return;
        var failed=entries.removeFirst();
        bytes-=failed.bytes();
        double skipped=failed.end()-accepted;
        excludedSeconds+=skipped;
        int remaining=entries.size();
        for(int i=0;i<remaining;i++) {
            var entry=entries.removeFirst();
            entries.addLast(new Entry(entry.end()-skipped,entry.frame(),entry.encoded()));
        }
    }
    void removeDestroyed(Set<DeviceId> devices,Set<UUID> branches) {
        var updated=new ArrayDeque<Entry>();int total=0;
        for(var entry:entries) {
            var frame=withoutDestroyed(entry.frame(),devices,branches);byte[] encoded=encodeFrame(frame);int size=12+encoded.length;
            updated.add(new Entry(entry.end(),frame,encoded));total+=size;
        }
        entries.clear();entries.addAll(updated);bytes=total;
    }
    static SimulationCoordinator.Frame withoutDestroyed(SimulationCoordinator.Frame frame,Set<DeviceId> devices,Set<UUID> branches) {
        return new SimulationCoordinator.Frame(frame.devices().stream().filter(d->!devices.contains(d.id())).toList(),
                frame.branches().stream().filter(b->!branches.contains(b.id()) && !devices.contains(b.positive().device())
                        && !devices.contains(b.negative().device()) && b.additionalPorts().stream().noneMatch(p->devices.contains(p.device()))).toList(),
                frame.blockers(),frame.removalDisposition(),frame.waveforms().stream().filter(c->!devices.contains(c.positive().device())
                        && !devices.contains(c.negative().device())).toList(),frame.recipients());
    }
    void resume() {
        if(missingStart>=0)excludedSeconds+=missingEnd-missingStart;
        missingStart=-1;missingEnd=-1;pause="";
    }
    byte[] saveTickBoundary(SimulationCoordinator.Frame installed) {
        var w=new NativeWireCodec.Writer(CHECKPOINT_BYTES);
        w.integer(installed==null?0:1);
        if(installed!=null)w.blob(encodeFrame(installed));
        w.integer(awaitingEffects==null?0:1);
        if(awaitingEffects!=null)w.blob(NativeWireCodec.encodeObservations(awaitingEffects));
        return w.bytes();
    }
    SimulationCoordinator.Frame restoreTickBoundary(byte[] payload,Revision revision,double clock) {
        var r=new NativeWireCodec.Reader(payload,CHECKPOINT_BYTES);
        var frame=r.count(1)==0?null:decodeFrame(r.blob());
        var effects=r.count(1)==0?null:NativeWireCodec.observations(new NativeWireCodec.Reader(r.blob()));
        r.finish();
        if(effects!=null && (frame==null || !effects.accepted().revision().equals(revision) || effects.accepted().end()!=clock))
            throw new IllegalArgumentException("Pending effects differ from saved physical state");
        awaitingEffects=effects;
        target=clock;
        return frame;
    }

    byte[] save(SimulationCoordinator.Frame installed) {
        var w=new NativeWireCodec.Writer(CHECKPOINT_BYTES);
        w.number(target).number(excludedSeconds).number(missingStart).number(missingEnd).string(pause);
        w.integer(installed==null?0:1);if(installed!=null)w.blob(encodeFrame(installed));
        w.integer(awaitingEffects==null?0:1);if(awaitingEffects!=null)w.blob(NativeWireCodec.encodeObservations(awaitingEffects));
        w.integer(entries.size());for(var entry:entries)w.number(entry.end()).blob(entry.encoded());
        return w.bytes();
    }
    SimulationCoordinator.Frame restore(byte[] payload,double accepted) {return restore(payload,accepted,true);}
    SimulationCoordinator.Frame restore(byte[] payload,double accepted,boolean requireInstalled) {
        var r=new NativeWireCodec.Reader(payload,CHECKPOINT_BYTES);
        double restoredTarget=r.number(),excluded=r.number(),start=r.number(),end=r.number();String reason=r.string();
        int hasInstalled=r.count(1);var installed=hasInstalled==0?null:decodeFrame(r.blob());
        var effects=r.count(1)==0?null:NativeWireCodec.observations(new NativeWireCodec.Reader(r.blob()));
        int count=r.count(MAX_BOUNDARIES),total=0;double cursor=accepted;
        var restored=new ArrayDeque<Entry>();
        for(int n=0;n<count;n++) {
            double stop=r.number();byte[] frame=r.blob();total=Math.addExact(total,12+frame.length);
            if(total>MAX_BYTES || stop<=cursor || stop-cursor>0.05+1e-9
                    || (n>0 && Math.abs(stop-cursor-0.05)>1e-9))throw new IllegalArgumentException("Invalid historical input interval");
            restored.add(new Entry(stop,decodeFrame(frame),frame));cursor=stop;
        }
        r.finish();
        if(restoredTarget<0 || excluded<0 || excluded>restoredTarget || cursor>restoredTarget-excluded+1e-9
                || (start==-1)!=(end==-1) || (start!=-1 && (start<0 || end<start || end!=restoredTarget || reason.isEmpty())))
            throw new IllegalArgumentException("Invalid input history cursor");
        if(Math.abs(cursor-((start>=0?start:restoredTarget)-excluded))>1e-9 || (requireInstalled && accepted>0 && installed==null))
            throw new IllegalArgumentException("Input history omits an uncaptured interval or installed boundary");
        if(effects!=null && (effects.accepted().end()!=accepted || installed==null))
            throw new IllegalArgumentException("Invalid pending world effects");
        entries.clear();entries.addAll(restored);bytes=total;target=restoredTarget;excludedSeconds=excluded;
        missingStart=start;missingEnd=end;pause=reason;awaitingEffects=effects;return installed;
    }
    static byte[] encodeFrame(SimulationCoordinator.Frame frame) {
        var w=new NativeWireCodec.Writer();w.integer(frame.removalDisposition().ordinal());
        w.integer(frame.devices().size());for(var d:frame.devices()) {
            w.uuid(d.id().instance()).longInteger(d.id().generation()).string(d.model());
            ports(w,d.ports());parameters(w,d.parameters());
        }
        w.integer(frame.branches().size());for(var b:frame.branches()) {
            w.uuid(b.id()).port(b.positive()).port(b.negative()).string(b.model());
            parameters(w,b.parameters());ports(w,b.additionalPorts());
        }
        w.integer(frame.blockers().size());for(var blocker:frame.blockers())w.string(blocker);
        w.integer(frame.waveforms().size());for(var c:frame.waveforms())w.uuid(c.channel()).port(c.positive()).port(c.negative());
        frame.recipients().encode(w);
        return w.bytes();
    }
    private static SimulationCoordinator.Frame decodeFrame(byte[] bytes) {
        var r=new NativeWireCodec.Reader(bytes);int removal=r.count(RemovalDisposition.values().length-1);
        var devices=new ArrayList<DeviceInput>();int count=r.count(4096);
        for(int n=0;n<count;n++)devices.add(new DeviceInput(new DeviceId(r.uuid(),r.longInteger()),r.string(),ports(r),parameters(r)));
        var branches=new ArrayList<Branch>();count=r.count(4096);
        for(int n=0;n<count;n++)branches.add(new Branch(r.uuid(),r.port(),r.port(),r.string(),parameters(r),ports(r)));
        var blockers=new ArrayList<String>();count=r.count(4096);for(int n=0;n<count;n++)blockers.add(r.string());
        var waves=new ArrayList<WaveformChannel>();count=r.count(32);
        for(int n=0;n<count;n++)waves.add(new WaveformChannel(r.uuid(),r.port(),r.port()));
        var recipients=WorldRecipients.decode(r);r.finish();
        // Reuse the domain reference/identity validation, even for blocked captures.
        if(blockers.isEmpty())new Topology(new Revision(new UUID(0,0),0,0,0),devices,branches,RemovalDisposition.values()[removal]);
        return new SimulationCoordinator.Frame(devices,branches,blockers,RemovalDisposition.values()[removal],waves,recipients);
    }
    private static void ports(NativeWireCodec.Writer w,List<PortId> ports) {
        w.integer(ports.size());for(var port:ports)w.port(port);
    }
    private static List<PortId> ports(NativeWireCodec.Reader r) {
        int count=r.count(4096);var ports=new ArrayList<PortId>();for(int n=0;n<count;n++)ports.add(r.port());return ports;
    }
    private static void parameters(NativeWireCodec.Writer w,Map<String,Double> parameters) {
        w.integer(parameters.size());new TreeMap<>(parameters).forEach((key,value)->w.string(key).number(value));
    }
    private static Map<String,Double> parameters(NativeWireCodec.Reader r) {
        int count=r.count(4096);var parameters=new HashMap<String,Double>();
        for(int n=0;n<count;n++)if(parameters.put(r.string(),r.number())!=null)throw new IllegalArgumentException("Duplicate parameter");
        return parameters;
    }
}
