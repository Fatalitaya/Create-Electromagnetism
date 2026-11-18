package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.content.instruments.*;
import com.electromagnetism.simulation.SimulationProtocol.*;
import com.george_vi.electroenergetics.devices.device.SimulatedDevice;
import com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Passive RLC propagation approximation: finite bandwidth, phase delay and
 * receiver loading all arise in the same circuit. No remote voltage is copied. */
public final class NativeRadioLinks {
    private record AntennaFact(DeviceId identity,net.minecraft.core.BlockPos position,double resistance,double capacitance) {}
    private record Bucket(int x,int y,int z) {
        static Bucket at(net.minecraft.core.BlockPos p){return new Bucket(Math.floorDiv(p.getX(),256),Math.floorDiv(p.getY(),256),Math.floorDiv(p.getZ(),256));}
    }
    private List<AntennaFact> cachedFacts=List.of();
    private List<DeviceInput> cachedDevices=List.of();
    private List<Branch> cachedBranches=List.of();
    public NativeRadioLinks(){}
    private static UUID branch(UUID owner,String role){return UUID.nameUUIDFromBytes((owner+"/radio/"+role).getBytes(StandardCharsets.UTF_8));}
    private static DeviceId identity(SimulatedDevice device){return ((CeeDeviceIdentity)device).electromagnetism$nativeIdentity();}
    private static PortId port(SimulatedDevice device,int terminal){return new PortId(identity(device),terminal);}
    public static void declare(SimulatedDevice device,List<DeviceInput> devices,Map<com.george_vi.electroenergetics.foundation.nodes.InWorldNode,PortId> ports){
        int external=device instanceof RadioDevice radio&&!radio.transmitter?4:2;
        var list=new ArrayList<PortId>();for(int i=0;i<10;i++)list.add(port(device,i));devices.add(new DeviceInput(identity(device),"ports",list,Map.of()));
        for(int i=0;i<external;i++)ports.put(new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(i,device.pos),port(device,i));
    }
    public void capture(InfrastructureSavedData sd,List<DeviceInput> devices,List<Branch> branches,List<WaveformChannel> waveforms,Set<DeviceId> ids,double time){
        var antennas=sd.deviceSD.getDevices().stream().filter(d->d instanceof AntennaDevice).map(d->(AntennaDevice)d).sorted(Comparator.comparing(d->identity(d).instance())).toList();
        for(var antenna:antennas) {
            if(!Double.isFinite(antenna.radiationResistance)||antenna.radiationResistance<=0||!Double.isFinite(antenna.couplingCapacitance)||antenna.couplingCapacitance<=0)throw new IllegalArgumentException("Invalid antenna R/C parameters");
            branches.add(new Branch(CeeNativeWorld.branchId(antenna,"coupling"),port(antenna,0),port(antenna,4),"capacitor",Map.of("capacitanceFarads",antenna.couplingCapacitance)));
            branches.add(new Branch(CeeNativeWorld.branchId(antenna,"main"),port(antenna,4),port(antenna,1),"resistor",Map.of("resistanceOhms",antenna.radiationResistance)));
        }
        var facts=antennas.stream().map(d->new AntennaFact(identity(d),d.pos.immutable(),d.radiationResistance,d.couplingCapacitance)).toList();
        if(!facts.equals(cachedFacts)) {
            var mediumDevices=new ArrayList<DeviceInput>();
            var mediumBranches=new ArrayList<Branch>();
            var buckets=new HashMap<Bucket,List<AntennaDevice>>();
            for(var antenna:antennas)buckets.computeIfAbsent(Bucket.at(antenna.pos),ignored->new ArrayList<>()).add(antenna);
            for(var a:antennas) {
                var bucket=Bucket.at(a.pos);
                var neighbours=new ArrayList<AntennaDevice>();
                for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++)
                    neighbours.addAll(buckets.getOrDefault(new Bucket(bucket.x()+dx,bucket.y()+dy,bucket.z()+dz),List.of()));
                neighbours.sort(Comparator.comparing(d->identity(d).instance()));
                for(var b:neighbours) {
                if(identity(a).instance().compareTo(identity(b).instance())>=0)continue;
                double distance=a.pos.getCenter().distanceTo(b.pos.getCenter());if(distance>256)continue;
                UUID pair=UUID.nameUUIDFromBytes((identity(a).instance()+"/medium/"+identity(b).instance()).getBytes(StandardCharsets.UTF_8));var id=new DeviceId(pair,0);
                var nodes=java.util.stream.IntStream.range(0,4).mapToObj(n->new PortId(id,n)).toList();mediumDevices.add(new DeviceInput(id,"ports",nodes,Map.of()));
                double attenuation=1.0/Math.max(1,distance),magnetizing=0.01;
                mediumBranches.add(new Branch(branch(pair,"coupling"),port(a,4),port(a,1),"coupled_windings",Map.of("primaryHenries",magnetizing+1e-6,"secondaryHenries",magnetizing*attenuation*attenuation+1e-6,"mutualHenries",magnetizing*attenuation,"primaryOhms",0.01,"secondaryOhms",0.01),List.of(nodes.get(0),nodes.get(3))));
                // Finite winding insulation also defines the line input voltage when
                // both winding and line inductors retain their initial flux.
                mediumBranches.add(new Branch(branch(pair,"insulation"),nodes.get(0),nodes.get(3),"resistor",Map.of("resistanceOhms",1e9)));
                // One passive section preserves summed R/L/C, not the four-section frequency response.
                double delay=Math.max(1e-6,distance/299792458.0);
                mediumBranches.add(new Branch(branch(pair,"line/r"),nodes.get(0),nodes.get(1),"resistor",Map.of("resistanceOhms",4*Math.max(0.001,distance*0.01/4))));
                mediumBranches.add(new Branch(branch(pair,"line/l"),nodes.get(1),nodes.get(2),"inductor",Map.of("inductanceHenries",50*delay)));
                mediumBranches.add(new Branch(branch(pair,"line/c"),nodes.get(2),nodes.get(3),"capacitor",Map.of("capacitanceFarads",delay/50)));
                mediumBranches.add(new Branch(branch(pair,"receive/positive"),nodes.get(2),port(b,4),"resistor",Map.of("resistanceOhms",50.0)));
                mediumBranches.add(new Branch(branch(pair,"receive/return"),nodes.get(3),port(b,1),"resistor",Map.of("resistanceOhms",0.01)));
            }
            }
            cachedFacts=facts;cachedDevices=List.copyOf(mediumDevices);cachedBranches=List.copyOf(mediumBranches);
        }
        for(var device:cachedDevices)if(!ids.add(device.id()))throw new IllegalArgumentException("Duplicate radio medium identity");
        devices.addAll(cachedDevices);branches.addAll(cachedBranches);
        for(var device:sd.deviceSD.getDevices())if(device instanceof RadioDevice radio) {
            if(!Double.isFinite(radio.carrierHz)||radio.carrierHz<100||radio.carrierHz>20000)throw new IllegalArgumentException("Radio carrier outside 100–20000 Hz");
            UUID owner=identity(device).instance();
            var antenna=antennas.stream().filter(a->a.pos.distSqr(radio.pos)<=16).min(Comparator.comparingDouble((AntennaDevice a)->a.pos.distSqr(radio.pos)).thenComparing(a->identity(a).instance())).orElse(null);
            if(radio.transmitter) {
                var microphone=sd.deviceSD.getDevices().stream().filter(d->d instanceof MicrophoneDevice&&d.pos.distSqr(radio.pos)<=16).map(d->(MicrophoneDevice)d).min(Comparator.comparingDouble((MicrophoneDevice d)->d.pos.distSqr(radio.pos)).thenComparing(d->identity(d).instance())).orElse(null);
                var parameters=new HashMap<String,Double>();
                if(microphone!=null)parameters.putAll(AudioNetworking.input(sd.level,microphone,time));
                else {parameters.put("startSeconds",time);parameters.put("sampleSeconds",1.0/16000);parameters.put("peakVolts",1.0);parameters.put("resistanceOhms",600.0);for(int n=0;n<800;n++)parameters.put("sample"+n,0.0);}
                parameters.put("frequencyHz",radio.carrierHz);parameters.put("phaseRadians",0.0);
                branches.add(new Branch(CeeNativeWorld.branchId(device,"main"),port(device,0),port(device,1),"radio_transmitter",parameters,List.of(port(device,4),port(device,5))));
            } else {
                branches.add(new Branch(CeeNativeWorld.branchId(device,"standby"),port(device,0),port(device,1),"resistor",Map.of("resistanceOhms",10000.0)));
                // Passive synchronous detector. Its output energy is drawn from
                // the received field; the supply pair powers the standby load.
                branches.add(new Branch(CeeNativeWorld.branchId(device,"main"),port(device,4),port(device,5),"carrier_mixer",Map.of("frequencyHz",radio.carrierHz,"phaseRadians",0.0,"ratio",2.0),List.of(port(device,6),port(device,3))));
                branches.add(new Branch(branch(owner,"audio/r"),port(device,6),port(device,7),"resistor",Map.of("resistanceOhms",600.0)));
                branches.add(new Branch(branch(owner,"audio/c"),port(device,7),port(device,3),"capacitor",Map.of("capacitanceFarads",1/(2*Math.PI*2000*600))));
                branches.add(new Branch(branch(owner,"audio/dc-block"),port(device,7),port(device,2),"capacitor",Map.of("capacitanceFarads",1e-5)));
                branches.add(new Branch(branch(owner,"audio/load"),port(device,2),port(device,3),"resistor",Map.of("resistanceOhms",10000.0)));
                waveforms.add(new WaveformChannel(CeeNativeWorld.branchId(device,"scope"),port(device,2),port(device,3)));
            }
            if(antenna!=null) {
                branches.add(new Branch(branch(owner,"antenna/positive"),port(device,4),port(antenna,0),"resistor",Map.of("resistanceOhms",0.01)));
                branches.add(new Branch(branch(owner,"antenna/return"),port(device,5),port(antenna,1),"resistor",Map.of("resistanceOhms",0.01)));
            } else branches.add(new Branch(branch(owner,"antenna/termination"),port(device,4),port(device,5),"resistor",Map.of("resistanceOhms",50.0)));
        }
    }
}
