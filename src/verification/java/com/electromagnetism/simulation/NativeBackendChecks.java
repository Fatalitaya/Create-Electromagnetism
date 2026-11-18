package com.electromagnetism.simulation;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static com.electromagnetism.simulation.SimulationProtocol.*;

/** Exercises the production typed backend, including the release JAR's implementation. */
public final class NativeBackendChecks {
    private NativeBackendChecks() {}
    private static void shaftBoundary(JniSimulationBackend backend) {
        for(double omega:new double[]{0,3,9,-3}) {
            UUID session=backend.create();var device=new DeviceId(UUID.randomUUID(),0);
            var p=java.util.stream.IntStream.range(0,3).mapToObj(n->new PortId(device,n)).toList();
            UUID shaft=UUID.randomUUID();
            backend.applyTopology(new Topology(revision(session,1,0,1),List.of(new DeviceInput(device,"ports",p,Map.of())),List.of(
                    new Branch(UUID.randomUUID(),p.get(1),p.get(0),"dc_voltage_source",Map.of("voltageVolts",12.0)),
                    new Branch(UUID.randomUUID(),p.get(1),p.get(2),"resistor",Map.of("resistanceOhms",2.0)),
                    new Branch(shaft,p.get(2),p.get(0),"shaft_boundary",Map.of("newtonMetresPerAmpere",2.0,"radiansPerSecond",omega)))));
            var accepted=backend.advanceTo(new Advance(revision(session,1,0,2),0,0.01,1000,10_000_000_000L));
            var observation=backend.readObservations(accepted.revision());
            var reading=observation.readings().stream().filter(r->r.branch().equals(shaft)).findFirst().orElseThrow();
            require(Math.abs(reading.torqueNewtonMetres().orElseThrow()-(12-2*omega))<1e-8,"JNI shaft torque mismatch");
            require(Math.abs(reading.activePower()-omega*reading.torqueNewtonMetres().orElseThrow())<1e-8,"JNI shaft power mismatch");
            var saved=backend.snapshot(accepted.revision());backend.destroy(session);backend.restore(session,saved);
            require(Arrays.equals(saved.payload(),backend.snapshot(saved.revision()).payload()),"JNI shaft restore changed work ledger");
            backend.setParameters(new Parameters(revision(session,1,1,3),List.of(),List.of(new BranchParameters(shaft,"shaft_boundary",Map.of("newtonMetresPerAmpere",2.0,"radiansPerSecond",omega+1)))));
            var next=backend.advanceTo(new Advance(revision(session,1,1,4),0.01,0.02,1000,10_000_000_000L));
            var edited=backend.readObservations(next.revision()).readings().stream().filter(r->r.branch().equals(shaft)).findFirst().orElseThrow();
            near(edited.torqueNewtonMetres().orElseThrow(),12-2*(omega+1),1e-8);
            backend.destroy(session);
        }
    }

    private static void require(boolean condition,String reason) { if (!condition) throw new AssertionError(reason); }
    private static void audioCapacity(JniSimulationBackend backend) {
        UUID id=backend.create();var device=new DeviceId(UUID.randomUUID(),0);
        var p=List.of(new PortId(device,0),new PortId(device,1));
        try {
            backend.applyTopology(new Topology(revision(id,1,0,1),List.of(new DeviceInput(device,"ports",p,Map.of())),
                    List.of(new Branch(UUID.randomUUID(),p.get(0),p.get(1),"dc_voltage_source",Map.of("voltageVolts",1.0)),
                            new Branch(UUID.randomUUID(),p.get(0),p.get(1),"resistor",Map.of("resistanceOhms",1000.0)))));
            var channels=java.util.stream.IntStream.range(0,32).mapToObj(n->new WaveformChannel(UUID.randomUUID(),p.get(0),p.get(1))).toList();
            backend.subscribeWaveforms(new WaveformSubscription(revision(id,1,0,2),channels,16000,2000,1024));
            var accepted=backend.advanceTo(new Advance(revision(id,1,0,3),0,0.05,100_000,30_000_000_000L));
            require(accepted.end()==0.05,"Audio interval did not complete");
            var output=backend.readObservations(accepted.revision()).waveforms();
            require(output.size()==32,"JNI lost audio channels");
            for(var wave:output) {
                require(wave.samples().size()==800 && wave.droppedSamples()==0,"JNI audio truncated a complete tick");
                near(wave.samples().getFirst().time(),1.0/16000,1e-12);
                near(wave.samples().getLast().time(),0.05,1e-12);
            }
        } finally {backend.destroy(id);}
    }
    private static void near(double actual,double expected,double tolerance) {
        require(Math.abs(actual-expected)<=tolerance,"Value mismatch: "+actual+" != "+expected);
    }
    private static void reject(Runnable action) {
        try { action.run(); throw new AssertionError("Invalid transaction accepted"); }
        catch (SimulationFailure expected) { require(!expected.diagnostic().reason().isBlank(),"Missing failure diagnostic"); }
    }
    private static Revision revision(UUID id,long topology,long parameters,long sequence) {
        return new Revision(id,topology,parameters,sequence);
    }
    private static void wattmeter(JniSimulationBackend backend) {
        UUID id=backend.create(),source=UUID.randomUUID(),meter=UUID.randomUUID(),load=UUID.randomUUID();
        DeviceId device=new DeviceId(UUID.randomUUID(),0);var p=java.util.stream.IntStream.range(0,3).mapToObj(n->new PortId(device,n)).toList();
        var descriptor=new DeviceInput(device,"ports",p,Map.of());
        var branches=List.of(new Branch(source,p.get(1),p.get(0),"dc_voltage_source",Map.of("voltageVolts",10.0)),
                new Branch(meter,p.get(1),p.get(0),"wattmeter_contact",Map.of("resistanceOhms",0.1),List.of(p.get(2))),
                new Branch(load,p.get(2),p.get(0),"resistor",Map.of("resistanceOhms",9.9)));
        try {
            backend.applyTopology(new Topology(revision(id,1,0,1),List.of(descriptor),branches));
            var advance=new Advance(revision(id,1,0,2),0,0.1,2000,10_000_000_000L);backend.advanceTo(advance);
            var r=backend.readObservations(advance.revision()).readings().stream().filter(x->x.branch().equals(meter)).findFirst().orElseThrow();
            near(r.activePower(),10,1e-9);near(r.totalAbsorbedEnergy(),1,1e-9);near(r.totalDissipatedEnergy(),0.01,1e-9);
            var saved=backend.snapshot(advance.revision());backend.destroy(id);backend.restore(id,saved);
            var next=new Advance(revision(id,1,0,3),0.1,0.2,2000,10_000_000_000L);backend.advanceTo(next);backend.advanceTo(next);
            r=backend.readObservations(next.revision()).readings().stream().filter(x->x.branch().equals(meter)).findFirst().orElseThrow();
            near(r.absorbedEnergy(),1,1e-9);near(r.totalAbsorbedEnergy(),2,1e-9);near(r.totalReturnedEnergy(),0,1e-9);
        } finally {backend.destroy(id);}
    }

    public static void run(Path library,String metadata) throws Exception {
        try (var missing=JniSimulationBackend.library(library.resolveSibling("absent-native-backend.dll"),metadata)) {
            require(missing.availability().status()==Status.UNAVAILABLE,"Missing library was available");
            reject(missing::create);
        }
        try (var mismatch=JniSimulationBackend.library(library,metadata+"-mismatch")) {
            require(mismatch.availability().status()==Status.UNAVAILABLE,"Metadata mismatch was accepted");
        }
        try (var backend=JniSimulationBackend.library(library,metadata)) {
            require(backend.availability().status()==Status.READY,"Typed JNI backend unavailable");
            wattmeter(backend);
            multiport(backend);
            windings(backend);
            shaftBoundary(backend);
            audioCapacity(backend);
            thermal(backend);
            protection(backend);



            UUID id=backend.create();
            DeviceId device=new DeviceId(UUID.randomUUID(),3);
            var ports=List.of(new PortId(device,0),new PortId(device,1),new PortId(device,2));
            var descriptor=new DeviceInput(device,"ports",ports,Map.of());
            UUID source=UUID.randomUUID(), resistor=UUID.randomUUID(), capacitor=UUID.randomUUID();
            List<Branch> branches=List.of(
                    new Branch(source,ports.get(1),ports.get(0),"dc_voltage_source",Map.of("voltageVolts",1.0)),
                    new Branch(resistor,ports.get(1),ports.get(2),"resistor",Map.of("resistanceOhms",1.0)),
                    new Branch(capacitor,ports.get(2),ports.get(0),"capacitor",Map.of("capacitanceFarads",1.0)));
            Topology topology=new Topology(revision(id,1,0,1),List.of(descriptor),branches);
            backend.applyTopology(topology);
            backend.applyTopology(topology);
            var advance=new Advance(revision(id,1,0,2),0,0.1,2000,10_000_000_000L);
            Accepted accepted=backend.advanceTo(advance);
            require(accepted.diagnostic().status()==Status.READY,"RC did not fully advance");
            Observations observed=backend.readObservations(advance.revision());
            Reading c=observed.readings().stream().filter(r -> r.branch().equals(capacitor)).findFirst().orElseThrow();
            // Integral of 1-exp(-t) over [0,0.1], independently of the endpoint voltage.
            near(c.meanVoltage(),1-(1-Math.exp(-0.1))/0.1,0.0001);
            Snapshot before=backend.snapshot(advance.revision());
            backend.advanceTo(advance);
            require(Arrays.equals(before.payload(),backend.snapshot(advance.revision()).payload()),"Typed duplicate advanced energy");
            reject(() -> backend.setParameters(new Parameters(revision(id,1,1,3),List.of(),
                    List.of(new BranchParameters(resistor,"resistor",Map.of("resistanceOhms",-1.0))))));
            require(Arrays.equals(before.payload(),backend.snapshot(advance.revision()).payload()),"Rejected batch mutated state");
            backend.setParameters(new Parameters(revision(id,1,1,3),List.of(),
                    List.of(new BranchParameters(resistor,"resistor",Map.of("resistanceOhms",2.0)))));
            reject(() -> backend.readObservations(advance.revision()));
            var shortRequest=new Advance(revision(id,1,1,4),0.1,0.2,2,10_000_000_000L);
            Accepted partial=backend.advanceTo(shortRequest);
            require(partial.diagnostic().status()==Status.PARTIAL,"Budget did not return a prefix");
            near(partial.end(),0.1002,1e-14);
            var readings=backend.readObservations(shortRequest.revision());
            for (Reading r:readings.readings()) {
                near(r.windowStart(),0.1,1e-15); near(r.windowEnd(),partial.end(),1e-15);
            }
            Snapshot checkpoint=backend.snapshot(shortRequest.revision());
            backend.destroy(id);
            reject(() -> backend.readObservations(shortRequest.revision()));
            Snapshot forged=new Snapshot(checkpoint.revision(),metadata,checkpoint.acceptedTime()+1,checkpoint.payload());
            reject(() -> backend.restore(id,forged));
            backend.restore(id,checkpoint);
            require(Arrays.equals(checkpoint.payload(),backend.snapshot(checkpoint.revision()).payload()),"Typed restore changed state");
            reject(() -> backend.readObservations(checkpoint.revision()));
            var continuation=new Advance(revision(id,1,1,5),checkpoint.acceptedTime(),0.2,2000,10_000_000_000L);
            require(backend.advanceTo(continuation).end()==0.2,"Typed restore could not continue");
            backend.setParameters(new Parameters(revision(id,1,2,6),List.of(),
                    List.of(new BranchParameters(capacitor,"capacitor",Map.of("capacitanceFarads",2.0)))));
            double[] editedEnergy=energy(backend.snapshot(revision(id,1,2,6)));
            require(editedEnergy[6]<0,"Capacitance edit did not account exported parameter work");
            List<Branch> remaining=branches.stream().filter(b -> !b.id().equals(capacitor)).map(b -> b.id().equals(resistor)
                    ? new Branch(b.id(),b.positive(),b.negative(),b.model(),Map.of("resistanceOhms",2.0)) : b).toList();
            reject(() -> backend.applyTopology(new Topology(revision(id,2,2,7),List.of(descriptor),remaining)));
            backend.applyTopology(new Topology(revision(id,2,2,7),List.of(descriptor),remaining,RemovalDisposition.EXPORT_STORED_ENERGY));
            double[] removedEnergy=energy(backend.snapshot(revision(id,2,2,7)));
            near(removedEnergy[1],0,1e-12);
            near(removedEnergy[7],editedEnergy[1],1e-12);
            backend.destroy(id);
            backend.destroy(id);

            UUID owned=backend.create();
            DeviceId loadId=new DeviceId(UUID.randomUUID(),4);
            var loadPorts=List.of(new PortId(loadId,0),new PortId(loadId,1));
            var load=new DeviceInput(loadId,"resistor",loadPorts,Map.of("resistanceOhms",5.0));
            backend.applyTopology(new Topology(revision(owned,1,0,1),List.of(load),List.of(
                    new Branch(UUID.randomUUID(),loadPorts.get(0),loadPorts.get(1),"dc_voltage_source",Map.of("voltageVolts",10.0)))));
            var loadAdvance=new Advance(revision(owned,1,0,2),0,0.001,100,10_000_000_000L);
            backend.advanceTo(loadAdvance);
            UUID loadBranch=NativeWireCodec.deviceBranchId(loadId);
            Reading loadReading=backend.readObservations(loadAdvance.revision()).readings().stream()
                    .filter(r -> r.branch().equals(loadBranch)).findFirst().orElseThrow();
            near(loadReading.activePower(),20.0,1e-10);
            var subscription=new WaveformSubscription(revision(owned,1,0,3),
                    List.of(new WaveformChannel(UUID.randomUUID(),loadPorts.get(0),loadPorts.get(1))),100,10,2);
            backend.subscribeWaveforms(subscription);
            backend.subscribeWaveforms(subscription);
            var sampled=new Advance(revision(owned,1,0,4),0.001,0.0611,1000,10_000_000_000L);
            require(backend.advanceTo(sampled).end()==sampled.requestedEnd(),"Waveform interval did not finish");
            Waveform waveform=backend.readObservations(sampled.revision()).waveforms().getFirst();
            require(waveform.samples().size()==2 && waveform.droppedSamples()==4,"Waveform capacity/overflow mismatch");
            for (WaveformSample sample:waveform.samples()) {
                require(sample.quality()==SampleQuality.RECONSTRUCTED,"Filtered sample quality missing");
                near(sample.value().orElseThrow(),10,1e-10);
            }
            var pool=Executors.newSingleThreadExecutor();
            try {
                var task=pool.submit(() -> {
                    try { backend.advanceTo(new Advance(revision(owned,1,0,5),0.0611,1000,1_000_000,10_000_000_000L)); }
                    catch (SimulationFailure expected) { /* Closing before acquisition is also valid. */ }
                });
                backend.destroy(owned);
                task.get(15,TimeUnit.SECONDS);
            } finally { pool.shutdownNow(); }
        }
        System.out.println("NATIVE_TYPED_BACKEND_PASSED: RC observations, SI device compilation, partial acceptance, atomic edits, restore, concurrent destroy and load failures");
    }
    private static void protection(JniSimulationBackend backend) {
        UUID session=backend.create();DeviceId device=new DeviceId(UUID.randomUUID(),0);
        var p=List.of(new PortId(device,0),new PortId(device,1));UUID fuse=UUID.randomUUID(), source=UUID.randomUUID();
        backend.applyTopology(new Topology(revision(session,1,0,1),List.of(new DeviceInput(device,"ports",p,Map.of())),List.of(
                new Branch(source,p.get(0),p.get(1),"dc_voltage_source",Map.of("voltageVolts",4.0)),
                new Branch(fuse,p.get(0),p.get(1),"thermal_fuse",Map.of("ambientOhms",2.0,"coefficientPerKelvin",0.0,
                        "heatCapacityJoulesPerKelvin",0.04,"conductanceWattsPerKelvin",0.5,"ambientKelvin",300.0,"tripKelvin",310.0)))));
        var advance=new Advance(revision(session,1,0,2),0,0.2,4000,10_000_000_000L);
        Accepted accepted=backend.advanceTo(advance);
        require(accepted.diagnostic().status()==Status.PARTIAL && accepted.end()<0.2,"Fuse failed to stop at physical boundary");
        near(accepted.end(),-0.08*Math.log(0.375),0.0001);
        ProtectionEvent event=backend.readObservations(advance.revision()).events().getFirst();
        require(event.branch().equals(fuse),"Wrong fuse event identity");near(event.temperatureKelvin(),310,1e-7);
        Snapshot saved=backend.snapshot(advance.revision());backend.destroy(session);backend.restore(session,saved);
        var retry=new Advance(revision(session,1,0,3),accepted.end(),0.2,4000,10_000_000_000L);
        near(backend.advanceTo(retry).end(),accepted.end(),0);
        require(backend.readObservations(retry.revision()).events().equals(List.of(event)),"Pending event did not survive restore");
        Revision ack=revision(session,2,0,4);
        reject(()->backend.acknowledgeEvents(ack,List.of(UUID.randomUUID())));
        reject(()->backend.acknowledgeDestruction(ack,List.of(event.id()),List.of(UUID.randomUUID())));
        backend.acknowledgeDestruction(ack,List.of(event.id()),List.of(source));backend.acknowledgeDestruction(ack,List.of(event.id()),List.of(source));
        var resume=new Advance(revision(session,2,0,5),accepted.end(),0.2,4000,10_000_000_000L);
        require(backend.advanceTo(resume).end()==0.2,"Fuse acknowledgement did not resume");
        var observations=backend.readObservations(resume.revision());require(observations.events().isEmpty(),"Acknowledged event replayed");
        require(observations.readings().isEmpty(),"Destroyed assembly still exists");
        backend.destroy(session);
    }
    private static void thermal(JniSimulationBackend backend) {
        UUID session=backend.create();DeviceId device=new DeviceId(UUID.randomUUID(),0);
        var p=List.of(new PortId(device,0),new PortId(device,1));UUID load=UUID.randomUUID();
        backend.applyTopology(new Topology(revision(session,1,0,1),List.of(new DeviceInput(device,"ports",p,Map.of())),List.of(
                new Branch(UUID.randomUUID(),p.get(0),p.get(1),"dc_voltage_source",Map.of("voltageVolts",4.0)),
                new Branch(load,p.get(0),p.get(1),"thermal_resistor",Map.of("ambientOhms",2.0,"coefficientPerKelvin",0.0,
                        "heatCapacityJoulesPerKelvin",0.04,"conductanceWattsPerKelvin",0.5,"ambientKelvin",300.0)))));
        var advance=new Advance(revision(session,1,0,2),0,0.08,2000,10_000_000_000L);
        require(backend.advanceTo(advance).end()==0.08,"Electrothermal advance failed");
        Reading reading=backend.readObservations(advance.revision()).readings().stream().filter(r->r.branch().equals(load)).findFirst().orElseThrow();
        near(reading.temperatureKelvin().orElseThrow(),300+16*(1-Math.exp(-1)),0.004);
        near(reading.activePower(),8,1e-9);
        Snapshot saved=backend.snapshot(advance.revision());backend.destroy(session);backend.restore(session,saved);
        require(Arrays.equals(saved.payload(),backend.snapshot(saved.revision()).payload()),"Thermal snapshot changed");
        backend.destroy(session);
    }
    private static void windings(JniSimulationBackend backend) {
        UUID session=backend.create();DeviceId device=new DeviceId(UUID.randomUUID(),0);
        var p=List.of(new PortId(device,0),new PortId(device,1),new PortId(device,2),new PortId(device,3));
        UUID winding=UUID.randomUUID(),load=UUID.randomUUID();
        var branches=List.of(
                new Branch(winding,p.get(0),p.get(1),"coupled_windings",
                        Map.of("primaryHenries",0.02,"secondaryHenries",0.02,"mutualHenries",0.01,"primaryOhms",1.0,"secondaryOhms",1.0),List.of(p.get(2),p.get(3))),
                new Branch(UUID.randomUUID(),p.get(0),p.get(1),"dc_voltage_source",Map.of("voltageVolts",1.0)),
                new Branch(load,p.get(2),p.get(3),"resistor",Map.of("resistanceOhms",1.0)));
        backend.applyTopology(new Topology(revision(session,1,0,1),List.of(new DeviceInput(device,"ports",p,Map.of())),branches));
        var advance=new Advance(revision(session,1,0,2),0,0.01,2000,10_000_000_000L);
        backend.advanceTo(advance);
        var readings=backend.readObservations(advance.revision()).readings();
        require(readings.stream().filter(r->r.branch().equals(load)).findFirst().orElseThrow().activePower()>0.001,
                "Coupled isolated secondary did not receive transient energy");
        Snapshot saved=backend.snapshot(advance.revision());backend.destroy(session);backend.restore(session,saved);
        require(Arrays.equals(saved.payload(),backend.snapshot(saved.revision()).payload()),"Winding snapshot changed");
        backend.destroy(session);
    }
    private static void multiport(JniSimulationBackend backend) {
        for (String model : List.of("npn_bipolar", "pnp_bipolar", "n_mosfet", "p_mosfet")) {
            boolean bipolar=model.endsWith("bipolar"), inverted=model.startsWith("p");
            double sign=inverted ? -1 : 1;
            UUID session=backend.create();
            DeviceId device=new DeviceId(UUID.randomUUID(),0);
            var ports=List.of(new PortId(device,0),new PortId(device,1),new PortId(device,2),
                    new PortId(device,3),new PortId(device,4));
            Map<String,Double> parameters=bipolar
                    ? Map.of("saturationAmperes",1e-12,"thermalVolts",0.025,"forwardBeta",100.0,
                        "reverseBeta",1.0,"baseEmitterFarads",1e-8,"baseCollectorFarads",0.5e-8)
                    : Map.of("betaAmperesPerVoltSquared",0.02,"thresholdVolts",2.0,"saturationAmperes",1e-12,
                        "thermalVolts",0.025,"gateSourceFarads",1e-8,"gateDrainFarads",0.5e-8);
            double current=bipolar ? 1e-12*Math.expm1(20)+2e-12 : 0.01;
            double supply=bipolar ? 1+1000*current : 6;
            double drive=bipolar ? 0.5+1000*(1e-14*Math.expm1(20)-1e-12) : 3;
            UUID transistor=UUID.randomUUID();
            var branches=List.of(
                    new Branch(UUID.randomUUID(),ports.get(3),ports.get(0),"dc_voltage_source",Map.of("voltageVolts",sign*supply)),
                    new Branch(UUID.randomUUID(),ports.get(3),ports.get(1),"resistor",Map.of("resistanceOhms",bipolar?1000.0:100.0)),
                    new Branch(UUID.randomUUID(),ports.get(4),ports.get(0),"dc_voltage_source",Map.of("voltageVolts",sign*drive)),
                    new Branch(UUID.randomUUID(),ports.get(4),ports.get(2),"resistor",Map.of("resistanceOhms",1000.0)),
                    new Branch(transistor,ports.get(1),ports.get(0),model,parameters,List.of(ports.get(2))));
            backend.applyTopology(new Topology(revision(session,1,0,1),List.of(new DeviceInput(device,"ports",ports,Map.of())),branches));
            var advance=new Advance(revision(session,1,0,2),0,0.01,2000,10_000_000_000L);
            require(backend.advanceTo(advance).end()==advance.requestedEnd(),"Multiport transient failed: "+model);
            var next=new Advance(revision(session,1,0,3),0.01,0.02,2000,10_000_000_000L);
            backend.advanceTo(next);
            var reading=backend.readObservations(next.revision()).readings().stream()
                    .filter(r -> r.branch().equals(transistor)).findFirst().orElseThrow();
            near(reading.meanVoltage(),sign*(bipolar?1:5),1e-6);
            near(reading.meanCurrent(),sign*current,1e-8);
            Snapshot saved=backend.snapshot(next.revision());
            backend.destroy(session);backend.restore(session,saved);
            require(Arrays.equals(saved.payload(),backend.snapshot(saved.revision()).payload()),"Multiport snapshot changed");
            backend.destroy(session);
        }
    }
    private static double[] energy(Snapshot snapshot) {
        var envelope=new NativeWireCodec.Reader(snapshot.payload());envelope.integer();
        var inner=new NativeWireCodec.Reader(envelope.blob());envelope.finish();
        inner.integer();inner.string();inner.revision();inner.number();inner.number();
        if (inner.integer()==1) for(int i=0;i<7;i++) inner.number();
        inner.number();
        double[] values=new double[8];for(int i=0;i<values.length;i++) values[i]=inner.number();
        return values;
    }
}
