package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.*;
import com.george_vi.electroenergetics.devices.device.SimulatedDevice;
import com.george_vi.electroenergetics.foundation.nodes.*;
import com.george_vi.electroenergetics.simulation.infrastructure.*;
import java.util.*;
import static com.electromagnetism.simulation.SimulationProtocol.*;

import static com.electromagnetism.integration.cee.nativebridge.CeeNativeWorld.branchId;
import static com.electromagnetism.integration.cee.nativebridge.CeeNativeWorld.meterBranchId;

import static com.electromagnetism.integration.cee.nativebridge.NativeModelParameters.*;

final class NativeInstrumentAdapter implements NativeDeviceAdapter {
    @Override public boolean capture(NativeCaptureContext context,SimulatedDevice device,DeviceId id) {
        var infrastructure=context.infrastructure();
        var devicesByBranch=context.devicesByBranch();
        var captureTime=context.captureTime();
        var descriptors=context.descriptors();
        var branches=context.branches();
        var waveforms=context.waveforms();
        var ports=context.ports();
        UUID main=branchId(device,"main");
        var p0=new PortId(id,0);var p1=new PortId(id,1);var internal=new PortId(id,2);
        if(device instanceof com.electromagnetism.content.instruments.RadioDevice || device instanceof com.electromagnetism.content.instruments.AntennaDevice) {
            NativeRadioLinks.declare(device,descriptors,ports);devicesByBranch.put(main,device);return true;
        } else         if(device instanceof com.electromagnetism.content.instruments.MicrophoneDevice microphone) {
            descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1),Map.of()));
            branches.add(new Branch(main,p0,p1,"audio_source",com.electromagnetism.content.instruments.AudioNetworking.input(infrastructure.level,microphone,captureTime)));devicesByBranch.put(main,device);
        } else         if(device instanceof com.electromagnetism.content.instruments.OscilloscopeDevice) {
            descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1),Map.of()));
            branches.add(new Branch(main,p0,p1,"resistor",Map.of("resistanceOhms",1e6)));
            waveforms.add(new WaveformChannel(branchId(device,"scope"),p0,p1));
        } else         if(device instanceof com.electromagnetism.content.instruments.SignalGeneratorDevice) {
            if(!(infrastructure.level.getBlockEntity(device.pos) instanceof com.electromagnetism.content.instruments.SignalGeneratorBlockEntity generator))throw new IllegalStateException("Missing signal generator settings at "+device.pos);
            var settings=generator.settings();
            boolean ac=settings.enabled() && settings.wave()!=com.electromagnetism.core.signal.SignalGenerator.Wave.DC;
            int harmonics=ac && settings.wave()!=com.electromagnetism.core.signal.SignalGenerator.Wave.SINE?8:1;
            var declared=new ArrayList<PortId>(List.of(p0,p1));
            for(int j=0;j<harmonics;j++)declared.add(new PortId(id,j+2));
            descriptors.add(new DeviceInput(id,"ports",declared,Map.of()));
            for(int j=0;j<harmonics;j++) {
                var output=declared.get(j+2);var input=j==harmonics-1?p1:declared.get(j+3);
                int harmonic=2*j+1;
                if(ac) {
                    double weight=switch(settings.wave()) {
                        case SINE -> 1.0;
                        case SQUARE -> 4.0/(Math.PI*harmonic)*(1.0-harmonic/16.0)/0.9603637867004527;
                        case TRIANGLE -> 8.0/(Math.PI*Math.PI*harmonic*harmonic)/0.974702508054903;
                        case DC -> throw new IllegalStateException("DC in periodic source");
                    };
                    // Finite Fourier synthesis uses Fejér weights for square waves and odd
                    // harmonics for triangle waves. Rust advances each harmonic's phase.
                    double phase=settings.wave()==com.electromagnetism.core.signal.SignalGenerator.Wave.TRIANGLE && j%2==1?Math.PI:0;
                    branches.add(new Branch(branchId(device,"generator/"+settings.wave()+"/"+harmonic),output,input,"sine_voltage_source",Map.of("offsetVolts",j==0?settings.offset():0.0,"peakVolts",settings.amplitude()*weight,"frequencyHz",settings.frequency()*harmonic,"phaseRadians",phase)));
                } else branches.add(new Branch(branchId(device,"generator/dc"),output,input,"dc_voltage_source",Map.of("voltageVolts",settings.enabled()?settings.offset()+settings.amplitude():0.0)));
            }
            branches.add(new Branch(main,p0,declared.get(2),"resistor",Map.of("resistanceOhms",50.0)));
            waveforms.add(new WaveformChannel(branchId(device,"scope"),p0,p1));
        } else         if(device instanceof com.george_vi.electroenergetics.content.energy_meter.EnergyMeterDevice
                || device instanceof com.george_vi.electroenergetics.content.energy_meter.TriPolarEnergyMeterDevice) {
            var meter=(NativeEnergyMeter)device;
            if(!meter.electromagnetism$counterProblem().isEmpty())throw new IllegalArgumentException(meter.electromagnetism$counterProblem());
            boolean three=device instanceof com.george_vi.electroenergetics.content.energy_meter.TriPolarEnergyMeterDevice;
            boolean closed=three?((com.george_vi.electroenergetics.content.energy_meter.TriPolarEnergyMeterDevice)device).isClosed:((com.george_vi.electroenergetics.content.energy_meter.EnergyMeterDevice)device).isClosed;
            var terminals=java.util.stream.IntStream.range(0,three?6:4).mapToObj(n->new PortId(id,n)).toList();
            descriptors.add(new DeviceInput(id,"ports",terminals,Map.of()));
            for(int n=0;n<terminals.size();n++)ports.put(new InWorldNode(n,device.pos),terminals.get(n));
            double contact=closed?0.001:1e12;
            main=meterBranchId(device,0);
            branches.add(new Branch(main,p0,p1,"wattmeter_contact",Map.of("resistanceOhms",contact),List.of(terminals.get(three?3:2))));
            branches.add(new Branch(branchId(device,"return_contact"),p1,terminals.get(three?4:3),"resistor",Map.of("resistanceOhms",contact)));
            branches.add(new Branch(branchId(device,"voltage_sense/0"),p0,p1,"resistor",Map.of("resistanceOhms",9999.0)));
            if(three) {
                var second=meterBranchId(device,1);devicesByBranch.put(second,device);
                branches.add(new Branch(second,terminals.get(2),p1,"wattmeter_contact",Map.of("resistanceOhms",contact),List.of(terminals.get(5))));
                branches.add(new Branch(branchId(device,"voltage_sense/1"),terminals.get(2),p1,"resistor",Map.of("resistanceOhms",9999.0)));
            }
        } else         if(device instanceof com.george_vi.electroenergetics.content.synchroscope.SynchroscopeDevice) {
            var terminals=java.util.stream.IntStream.range(0,6).mapToObj(n->new PortId(id,n)).toList();
            descriptors.add(new DeviceInput(id,"ports",terminals,Map.of()));
            double rating=com.george_vi.electroenergetics.config.CEEConfigs.server().voltageValues.maxVoltmeterVoltage.get();
            double loss=rating*rating/1e6;
            for(int pin=0;pin<6;pin++) {
                ports.put(new InWorldNode(pin,device.pos),terminals.get(pin));
                UUID probe=branchId(device,"sync/"+pin);devicesByBranch.put(probe,device);
                int next=pin/3*3+(pin+1)%3;
                branches.add(new Branch(probe,terminals.get(pin),terminals.get(next),"thermal_fuse",thermal(1e6,loss/100,loss/100,393.15)));
            }
        } else         if(device instanceof com.george_vi.electroenergetics.content.frequency_meter.FrequencyMeterDevice) {
            descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1),Map.of()));
            double rating=com.george_vi.electroenergetics.config.CEEConfigs.server().voltageValues.maxVoltmeterVoltage.get();
            double ratedLoss=rating*rating/1e6;
            branches.add(new Branch(main,p0,p1,"thermal_fuse",thermal(1e6,ratedLoss/100,ratedLoss/100,393.15)));
        } else         if(device instanceof com.george_vi.electroenergetics.content.gauge.GaugeDevice gauge) {
            descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1),Map.of()));
            branches.add(new Branch(main,p0,p1,"thermal_fuse",NativeProtection.gauge(gauge.voltmeter)));
        } else {return false;}
        context.ports().put(new InWorldNode(0,device.pos),p0);context.ports().put(new InWorldNode(1,device.pos),p1);
        context.devicesByBranch().put(main,device);
        return true;
    }
}
