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
    private NativeRadioLinks(){}
    private static UUID branch(UUID owner,String role){return UUID.nameUUIDFromBytes((owner+"/radio/"+role).getBytes(StandardCharsets.UTF_8));}
    private static DeviceId identity(SimulatedDevice device){return ((CeeDeviceIdentity)device).electromagnetism$nativeIdentity();}
    private static PortId port(SimulatedDevice device,int terminal){return new PortId(identity(device),terminal);}
    public static void declare(SimulatedDevice device,List<DeviceInput> devices,Map<com.george_vi.electroenergetics.foundation.nodes.InWorldNode,PortId> ports){
        int external=device instanceof RadioDevice radio&&!radio.transmitter?4:2;
        var list=new ArrayList<PortId>();for(int i=0;i<10;i++)list.add(port(device,i));devices.add(new DeviceInput(identity(device),"ports",list,Map.of()));
        for(int i=0;i<external;i++)ports.put(new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(i,device.pos),port(device,i));
    }
    public static void capture(InfrastructureSavedData sd,List<DeviceInput> devices,List<Branch> branches,List<WaveformChannel> waveforms,Set<DeviceId> ids,double time){
        var antennas=sd.deviceSD.getDevices().stream().filter(d->d instanceof AntennaDevice).map(d->(AntennaDevice)d).sorted(Comparator.comparing(d->identity(d).instance())).toList();
        if(antennas.size()>16)throw new IllegalArgumentException("Radio medium supports at most 16 loaded antennas per dimension");
        for(var antenna:antennas) {
            if(!Double.isFinite(antenna.radiationResistance)||antenna.radiationResistance<=0||!Double.isFinite(antenna.couplingCapacitance)||antenna.couplingCapacitance<=0)throw new IllegalArgumentException("Invalid antenna R/C parameters");
            branches.add(new Branch(CeeNativeWorld.branchId(antenna,"coupling"),port(antenna,0),port(antenna,4),"capacitor",Map.of("capacitanceFarads",antenna.couplingCapacitance)));
            branches.add(new Branch(CeeNativeWorld.branchId(antenna,"main"),port(antenna,4),port(antenna,1),"resistor",Map.of("resistanceOhms",antenna.radiationResistance)));
        }
        for(int i=0;i<antennas.size();i++)for(int j=i+1;j<antennas.size();j++) {
            var a=antennas.get(i);var b=antennas.get(j);double distance=a.pos.getCenter().distanceTo(b.pos.getCenter());if(distance>256)continue;
            UUID pair=UUID.nameUUIDFromBytes((identity(a).instance()+"/medium/"+identity(b).instance()).getBytes(StandardCharsets.UTF_8));var id=new DeviceId(pair,0);
            if(!ids.add(id))throw new IllegalArgumentException("Duplicate radio medium identity");
            var nodes=java.util.stream.IntStream.range(0,12).mapToObj(n->new PortId(id,n)).toList();devices.add(new DeviceInput(id,"ports",nodes,Map.of()));
            double attenuation=1.0/Math.max(1,distance),magnetizing=0.01;
            branches.add(new Branch(branch(pair,"coupling"),port(a,4),port(a,1),"coupled_windings",Map.of("primaryHenries",magnetizing+1e-6,"secondaryHenries",magnetizing*attenuation*attenuation+1e-6,"mutualHenries",magnetizing*attenuation,"primaryOhms",0.01,"secondaryOhms",0.01),List.of(nodes.get(0),nodes.get(9))));
            // The effective group delay is distance/c; four passive sections are
            // a declared band-limited line approximation, not a full-space field solve.
            double delay=Math.max(1e-6,distance/299792458.0),z=50;
            for(int section=0;section<4;section++) {
                var input=nodes.get(section*2);var copper=nodes.get(section*2+1);var output=nodes.get(section*2+2);
                branches.add(new Branch(branch(pair,"line/"+section+"/r"),input,copper,"resistor",Map.of("resistanceOhms",Math.max(0.001,distance*0.01/4))));
                branches.add(new Branch(branch(pair,"line/"+section+"/l"),copper,output,"inductor",Map.of("inductanceHenries",z*delay/4)));
                branches.add(new Branch(branch(pair,"line/"+section+"/c"),output,nodes.get(9),"capacitor",Map.of("capacitanceFarads",delay/(4*z))));
            }
            branches.add(new Branch(branch(pair,"receive/positive"),nodes.get(8),port(b,4),"resistor",Map.of("resistanceOhms",50.0)));
            branches.add(new Branch(branch(pair,"receive/return"),nodes.get(9),port(b,1),"resistor",Map.of("resistanceOhms",0.01)));
        }
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
