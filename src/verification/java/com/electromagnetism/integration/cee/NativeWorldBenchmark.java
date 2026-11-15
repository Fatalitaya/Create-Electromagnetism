package com.electromagnetism.integration.cee;

import com.electromagnetism.integration.cee.nativebridge.CeeNativeWorld;
import com.electromagnetism.simulation.*;
import com.george_vi.electroenergetics.devices.device.*;
import com.george_vi.electroenergetics.foundation.nodes.InWorldNode;
import com.george_vi.electroenergetics.simulation.infrastructure.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import java.nio.file.*;
import java.util.*;
import static com.electromagnetism.simulation.SimulationProtocol.*;

/** Opt-in measurements on actual CEE owners/wires, separate from physical assertions. */
public final class NativeWorldBenchmark {
    private NativeWorldBenchmark() {}
    public static void run(ServerLevel level,DevicesSavedData devices) throws Exception {
        var positions=new ArrayList<BlockPos>();var sd=InfrastructureSavedData.load(level);
        for(int n=0;n<512;n++)positions.add(new BlockPos(2*(n%32),240,32+2*(n/32)));
        for(var p:positions)level.getChunkAt(p);
        String metadata=net.neoforged.fml.ModList.get().getModContainerById("electromagnetism").orElseThrow().getModInfo().getVersion().toString();
        try(var backend=JniSimulationBackend.adaptivePackaged(metadata)) {
            for(var p:positions)if(devices.getDevice(p)!=null)throw new AssertionError("Benchmark position occupied: "+p);
            var sourceTag=new CompoundTag();sourceTag.putDouble("Voltage",1);
            var source=devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_BATTERY.get(),positions.getFirst(),sourceTag);
            sd.registerOrUpdateNodes(positions.getFirst(),List.of(0,1));
            for(int n=1;n<=510;n++) {
                var tag=new CompoundTag();tag.putDouble("Resistance",1);
                devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_RESISTOR.get(),positions.get(n),tag);
                sd.registerOrUpdateNodes(positions.get(n),List.of(0,1));
            }
            devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.GROUND_ROD.get(),positions.get(511),new CompoundTag());
            sd.registerOrUpdateNodes(positions.get(511),List.of(0));
            double totalResistance=510.001;
            for(int n=0;n<=510;n++) {
                var wire=WireData.ofLength(com.george_vi.electroenergetics.CEEWireTypes.STANDARD.get(),2);totalResistance+=wire.getResistance();
                sd.connect(new InWorldNode(n==0?0:1,positions.get(n)),new InWorldNode(n==510?1:0,positions.get((n+1)%511)),wire);
            }
            source.voltage=totalResistance;
            var world=new CeeNativeWorld(sd);var capture=new ArrayList<Double>();var advance=new ArrayList<Double>();var decode=new ArrayList<Double>();var apply=new ArrayList<Double>();
            SimulationCoordinator.Frame frame=null;
            for(int n=0;n<20;n++){long begin=System.nanoTime();frame=world.capture();capture.add((System.nanoTime()-begin)/1e6);}
            if(frame==null || !frame.blockers().isEmpty() || frame.branches().size()!=1024)throw new AssertionError("World benchmark topology: "+frame);
            UUID session=backend.create();var revision=new Revision(session,1,1,1);long build=System.nanoTime();
            backend.applyBoundary(new Topology(revision,frame.devices(),frame.branches(),frame.removalDisposition()));
            double compileMs=(System.nanoTime()-build)/1e6,time=0,maxError=0;long accepted=0,rejected=0,discarded=0;
            var sink=CeeNativeWorld.class.getDeclaredMethod("apply",Observations.class);sink.setAccessible(true);
            for(int n=0;n<20;n++) {
                revision=new Revision(session,1,1,revision.sequence()+1);long begin=System.nanoTime();
                var result=backend.advanceTo(new Advance(revision,time,time+0.05,10000,20_000_000));advance.add((System.nanoTime()-begin)/1e6);time=result.end();
                accepted+=result.work().acceptedSteps();rejected+=result.work().rejectedSteps();discarded+=result.work().discardedSteps();
                begin=System.nanoTime();var observations=backend.readObservations(revision);decode.add((System.nanoTime()-begin)/1e6);
                begin=System.nanoTime();sink.invoke(world,observations);apply.add((System.nanoTime()-begin)/1e6);
                if(result.end()>result.start())for(int load=1;load<=510;load++) {
                    UUID id=CeeNativeWorld.branchId(devices.getDevice(positions.get(load)),"main");
                    maxError=Math.max(maxError,Math.abs(world.reading(id).orElseThrow().meanCurrent()-1));
                }
            }
            if(maxError>1e-8)throw new AssertionError("World benchmark differs from independent series resistance: "+maxError);
            var report=new com.google.gson.JsonObject();report.addProperty("topology","510 series loads, 511 thermal wires, finite source, isolated earth rod");
            report.addProperty("nativeBranches",1024);report.addProperty("profile","packaged release native / server JVM");report.addProperty("cpuLogicalProcessors",Runtime.getRuntime().availableProcessors());
            report.addProperty("jniBoundaryCompileMs",compileMs);report.addProperty("acceptedSteps",accepted);report.addProperty("rejectedSteps",rejected);report.addProperty("discardedSteps",discarded);
            report.addProperty("acceptedSeconds",time);report.addProperty("uncompletedSeconds",1-time);report.addProperty("maxCurrentErrorAmperes",maxError);
            report.addProperty("worldApplyScope","readings and world dirty state for passive owners; excludes lamps, packets and damage events");
            report.addProperty("jvmHeapUsedBytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());
            report.add("captureMs",percentiles(capture));report.add("jniAdvanceIncludingSolveMs",percentiles(advance));report.add("jniObservationsAndDecodeMs",percentiles(decode));report.add("worldApplyMs",percentiles(apply));
            Files.writeString(Path.of("native-world-benchmark.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));
            backend.destroy(session);System.out.println("NATIVE_WORLD_BENCHMARK_RECORDED: "+report);
        } finally {for(var p:positions)devices.removeDevice(p);}
    }
    private static com.google.gson.JsonObject percentiles(List<Double> values) {
        values.sort(Double::compare);var p=new com.google.gson.JsonObject();p.addProperty("samples",values.size());p.addProperty("p50",values.get(9));p.addProperty("p95",values.get(18));p.addProperty("p99",values.get(19));return p;
    }
}
