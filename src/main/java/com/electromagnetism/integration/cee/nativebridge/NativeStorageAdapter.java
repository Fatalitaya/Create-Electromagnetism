package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.*;
import com.electromagnetism.config.ModConfigs;
import com.george_vi.electroenergetics.content.creative_battery.CreativeBatteryDevice;
import com.george_vi.electroenergetics.devices.device.SimulatedDevice;
import com.george_vi.electroenergetics.foundation.nodes.*;
import com.george_vi.electroenergetics.simulation.infrastructure.*;
import java.util.*;
import static com.electromagnetism.simulation.SimulationProtocol.*;

import static com.electromagnetism.integration.cee.nativebridge.CeeNativeWorld.branchId;

import static com.electromagnetism.integration.cee.nativebridge.NativeModelParameters.*;

final class NativeStorageAdapter implements NativeDeviceAdapter {
    @Override public boolean capture(NativeCaptureContext context,SimulatedDevice device,DeviceId id) {
        var infrastructure=context.infrastructure();
        var devicesByBranch=context.devicesByBranch();
        var descriptors=context.descriptors();
        var branches=context.branches();
        var ports=context.ports();
        UUID main=branchId(device,"main");
        var p0=new PortId(id,0);var p1=new PortId(id,1);var internal=new PortId(id,2);
        if(device instanceof com.george_vi.electroenergetics.content.converter.ConverterDevice converter) {
            if(!(infrastructure.level.getBlockEntity(device.pos) instanceof NativeConverterBoundary boundary))throw new IllegalArgumentException("Missing converter external boundary");
            if(!boundary.electromagnetism$converter().problem().isEmpty())throw new IllegalArgumentException(boundary.electromagnetism$converter().problem());
            var buffer=new PortId(id,2);var bufferReturn=new PortId(id,3);var output=new PortId(id,4);var gate=new PortId(id,5);
            double capacity=ModConfigs.server().nativeConverterBufferJoules.getF();
            descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1,buffer,bufferReturn,output,gate),Map.of()));
            branches.add(new Branch(branchId(device,"fe-buffer"),buffer,bufferReturn,"capacitor",Map.of("capacitanceFarads",2*capacity/(48*48))));
            branches.add(new Branch(branchId(device,"buffer-gate"),gate,buffer,"battery_contact",Map.of("resistanceOhms",0.01,"fullVolts",48.0,"taperVolts",2.4),List.of(bufferReturn)));
            if(converter.isSource) {
                if(!Double.isFinite(converter.voltage)||converter.voltage<0)throw new IllegalArgumentException("Invalid converter output voltage");
                branches.add(new Branch(main,output,p1,"regulated_converter",Map.of("outputVolts",converter.voltage,"kneeVolts",24.0,"efficiency",(double)ModConfigs.server().nativeConverterEfficiency.getF()),List.of(gate,bufferReturn)));
                branches.add(new Branch(branchId(device,"output-copper"),output,p0,"resistor",Map.of("resistanceOhms",0.1)));
            } else {
                // A full bridge charges a finite DC link from either input polarity.
                // Use the same quasistatic bridge as the pump; the DC link
                // carries storage, without redundant ideal capacitor constraints.
                Map<String,Double> diode=Map.of("saturationAmperes",1e-9,"thermalVolts",0.05,"junctionFarads",0.0);
                branches.add(new Branch(main,p0,gate,"diode",diode));
                branches.add(new Branch(branchId(device,"rectifier/1"),p1,gate,"diode",diode));
                branches.add(new Branch(branchId(device,"rectifier/2"),bufferReturn,p0,"diode",diode));
                branches.add(new Branch(branchId(device,"rectifier/3"),bufferReturn,p1,"diode",diode));
            }
            devicesByBranch.put(main,device);
        } else         if(device instanceof com.george_vi.electroenergetics.content.accumulator.AccumulatorDevice accumulator) {
            int count=accumulator.isDoubleCell?2:1;
            var declared=new ArrayList<PortId>();
            double full=ModConfigs.server().nativeCellFullVolts.getF();
            for(int cell=0;cell<count;cell++) {
                var positive=new PortId(id,cell*2);var negative=new PortId(id,cell*2+1);
                var copper=new PortId(id,4+cell*3);var chemical=new PortId(id,5+cell*3);var polarization=new PortId(id,6+cell*3);
                declared.addAll(List.of(positive,negative,copper,chemical,polarization));
                ports.put(new InWorldNode(cell*2,device.pos),positive);ports.put(new InWorldNode(cell*2+1,device.pos),negative);
                UUID loss=branchId(device,"cell/"+cell+"/copper");
                branches.add(new Branch(loss,positive,copper,"thermal_resistor",Map.of("ambientOhms",(double)ModConfigs.server().nativeCellResistanceOhms.getF(),"coefficientPerKelvin",0.0039,"heatCapacityJoulesPerKelvin",100.0,"conductanceWattsPerKelvin",1.0,"ambientKelvin",293.15)));
                branches.add(new Branch(branchId(device,"cell/"+cell+"/gate"),polarization,chemical,"battery_contact",Map.of("resistanceOhms",0.001,"fullVolts",full,"taperVolts",full*0.05),List.of(negative)));
                branches.add(new Branch(branchId(device,"cell/"+cell+"/polarization-r"),copper,polarization,"resistor",Map.of("resistanceOhms",0.05)));
                branches.add(new Branch(branchId(device,"cell/"+cell+"/polarization-c"),copper,polarization,"capacitor",Map.of("capacitanceFarads",0.2)));
                branches.add(new Branch(branchId(device,"cell/"+cell+"/charge"),chemical,negative,"capacitor",Map.of("capacitanceFarads",ModConfigs.server().nativeCellCapacityCoulombs.getF()/full)));
                devicesByBranch.put(loss,device);
            }
            descriptors.add(new DeviceInput(id,"ports",declared,Map.of()));return true;
        } else         if(device instanceof CreativeBatteryDevice source) {
            descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1,internal),Map.of()));
            if(source.acFrequency==0) branches.add(new Branch(branchId(device,"source/dc"),internal,p1,"dc_voltage_source",Map.of("voltageVolts",source.voltage)));
            else {
                double phase=Math.toRadians(source.phaseOffset)+Math.PI/2+(source.voltage<0?Math.PI:0);
                // Changing configured phase replaces the ideal source at the accepted boundary.
                // Frequency is deliberately absent from its identity: edits preserve accepted phase.
                branches.add(new Branch(branchId(device,"source/ac/"+Double.toHexString(phase)),internal,p1,"sine_voltage_source",Map.of("offsetVolts",0.0,"peakVolts",Math.abs(source.voltage),
                        "frequencyHz",source.acFrequency,"phaseRadians",phase)));
            }
            branches.add(new Branch(branchId(device,"main"),p0,internal,"resistor",Map.of("resistanceOhms",0.001)));
        } else         if(device instanceof com.george_vi.electroenergetics.content.transmission_distribution.hv_capacitor.HVCapacitorDevice capacitor) {
            descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1,internal),Map.of()));
            var positive=p0;var negative=p1;
            if(capacitor.facing!=null) {
                var connector=infrastructure.deviceSD.getDevice(device.pos.relative(capacitor.facing),com.george_vi.electroenergetics.content.connector.DoubleConnectorDevice.class);
                if(connector!=null) {
                    var connectorId=((CeeDeviceIdentity)connector).electromagnetism$nativeIdentity();
                    positive=new PortId(connectorId,0);negative=new PortId(connectorId,1);
                }
            }
            branches.add(new Branch(main,positive,internal,"capacitor",Map.of("capacitanceFarads",capacitor.capacitance)));
            storageParasitics(context,device,positive,negative,internal,capacitor.maxVoltage.getAsDouble(),branches);
        } else         if(device instanceof com.george_vi.electroenergetics.content.electronic_components.capacitor.CapacitorDevice capacitor) {
            descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1,internal),Map.of()));
            branches.add(new Branch(main,p0,internal,"capacitor",Map.of("capacitanceFarads",capacitor.capacitance)));
            storageParasitics(context,device,p0,p1,internal,capacitor.maxVoltage.getAsDouble(),branches);
        } else if (device instanceof com.electromagnetism.content.electronics.PowerChokeDevice) {
            descriptors.add(new DeviceInput(id, "ports", List.of(p0, p1, internal), Map.of()));
            branches.add(new Branch(main, p0, internal, "inductor", Map.of("inductanceHenries", 0.4)));
            // At 10 A the winding dissipates 40 W for a 100 K rise.
            storageParasitics(context, device, p0, p1, internal, 500.0, thermal(0.4, 40, 0.4, 393.15), branches);
        } else         if(device instanceof com.george_vi.electroenergetics.content.electronic_components.inductor.InductorDevice inductor) {
            descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1,internal),Map.of()));
            branches.add(new Branch(main,p0,internal,"inductor",Map.of("inductanceHenries",inductor.inductance)));
            storageParasitics(context,device,p0,p1,internal,inductor.maxVoltage.getAsDouble(),branches);
        } else {return false;}
        context.ports().put(new InWorldNode(0,device.pos),p0);context.ports().put(new InWorldNode(1,device.pos),p1);
        context.devicesByBranch().put(main,device);
        return true;
    }
}
