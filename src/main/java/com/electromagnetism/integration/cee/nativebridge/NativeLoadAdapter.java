package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.*;
import com.electromagnetism.config.ModConfigs;
import com.electromagnetism.content.thermal.heater.HeatingCoilDevice;
import com.electromagnetism.content.lighting.fixture.LightFixtureDevice;
import com.electromagnetism.content.lighting.bulb.ILightBulb;
import com.george_vi.electroenergetics.content.electronic_components.resistor.ResistorDevice;
import com.george_vi.electroenergetics.config.CEEConfigs;
import com.george_vi.electroenergetics.devices.device.SimulatedDevice;
import com.george_vi.electroenergetics.foundation.nodes.*;
import com.george_vi.electroenergetics.simulation.infrastructure.*;
import java.util.*;
import static com.electromagnetism.simulation.SimulationProtocol.*;

import static com.electromagnetism.integration.cee.nativebridge.CeeNativeWorld.branchId;

import static com.electromagnetism.integration.cee.nativebridge.NativeModelParameters.*;

final class NativeLoadAdapter implements NativeDeviceAdapter {
    @Override public boolean capture(NativeCaptureContext context,SimulatedDevice device,DeviceId id) {
        var devicesByBranch=context.devicesByBranch();
        var descriptors=context.descriptors();
        var branches=context.branches();
        var ports=context.ports();
        UUID main=branchId(device,"main");
        var p0=new PortId(id,0);var p1=new PortId(id,1);var internal=new PortId(id,2);
        if(device instanceof com.electromagnetism.content.acoustics.bell.ElectricBellDevice) {
            descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1,internal),Map.of()));
            // The bell coil has 20 Ω resistance and 50 mH inductance. Accepted power drives its
            // sound envelope.
            branches.add(new Branch(main,p0,internal,"thermal_fuse",thermal(20,20,0.2,393.15)));
            UUID magnetic=branchId(device,"coil-flux");devicesByBranch.put(magnetic,device);
            branches.add(new Branch(magnetic,internal,p1,"inductor",Map.of("inductanceHenries",0.05)));
        } else         if(device instanceof com.george_vi.electroenergetics.content.buzzer.BuzzerDevice) {
            descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1),Map.of()));
            branches.add(new Branch(main,p0,p1,"thermal_fuse",thermal(1000,2,0.1,393.15)));
        } else         if(device instanceof com.george_vi.electroenergetics.content.indicator_bulb.IndicatorBulbDevice indicator) {
            if(indicator.side<0 || indicator.side>2)throw new IllegalArgumentException("Invalid indicator side at "+device.pos);
            var terminals=java.util.stream.IntStream.range(0,4).mapToObj(n->new PortId(id,n)).toList();
            descriptors.add(new DeviceInput(id,"ports",terminals,Map.of()));for(int n=0;n<4;n++)ports.put(new InWorldNode(n,device.pos),terminals.get(n));
            for(int side=0;side<2;side++)if(indicator.side==2 || indicator.side==side) {
                UUID branch=side==0?main:branchId(device,"indicator/1");devicesByBranch.put(branch,device);
                branches.add(new Branch(branch,terminals.get(2*side),terminals.get(2*side+1),"thermal_fuse",NativeProtection.indicator()));
            }
        } else         if(device instanceof com.george_vi.electroenergetics.content.bulb.BulbDevice bulb) {
            descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1),Map.of()));
            double resistance=com.george_vi.electroenergetics.config.CEEConfigs.server().resistanceValues.bulbResistance.get();
            if (!bulb.destroyed) {
                double coolingWattsPerKelvin = 285.0 * 285.0 / resistance / 300.0;
                // Calibrate the configured resistance for a 0.75 s thermal time constant.
                double heatCapacityJoulesPerKelvin = coolingWattsPerKelvin * 0.75;
                branches.add(new Branch(main, p0, p1, "thermal_fuse",
                        thermal(resistance, heatCapacityJoulesPerKelvin, coolingWattsPerKelvin, 693.15)));
            }
        } else         if(device instanceof com.george_vi.electroenergetics.content.resistive_heater.ResistiveHeaterDevice) {
            descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1),Map.of()));
            double resistance=com.george_vi.electroenergetics.config.CEEConfigs.server().resistanceValues.resistiveHeaterResistance.get();
            branches.add(new Branch(main,p0,p1,"thermal_fuse",thermal(resistance,200,10,1093.15)));
        } else         if(device instanceof com.george_vi.electroenergetics.content.potentiometer.PotentiometerDevice potentiometer) {
            if(!Double.isFinite(potentiometer.resistance) || potentiometer.resistance<=0 || !Float.isFinite(potentiometer.progress) || potentiometer.progress<0 || potentiometer.progress>1)
                throw new IllegalArgumentException("Potentiometer requires positive resistance and wiper position in [0,1]");
            descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1,internal),Map.of()));ports.put(new InWorldNode(2,device.pos),internal);
            double watts = CEEConfigs.server().powerValues.potentiometerMaxPower.get();
            // A 1 mΩ contact at each end keeps the wiper inside the resistive track at both stops.
            double total = potentiometer.resistance + 0.002;
            var track = new HashMap<>(ratedResistance(total, watts));
            track.put("portCount", 3.0);
            for (int n = 0; n < 32; n++) track.put("position" + n, 0.0);
            track.put("position1", 1.0);
            track.put("position2", (0.001 + potentiometer.progress * potentiometer.resistance) / total);
            branches.add(new Branch(main, p0, internal, "tapped_wire", track, List.of(p1)));
        } else         if(device instanceof ResistorDevice resistor && resistor.properties!=null) {
            descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1),Map.of()));
            double ohms = resistor.properties.resistance;
            var parameters = resistor.creative ? Map.of("resistanceOhms", ohms)
                    : ratedResistance(ohms, CEEConfigs.server().powerValues.resistorMaxPower.get());
            branches.add(new Branch(main, p0, p1, resistor.creative ? "resistor" : "thermal_fuse", parameters));
        } else         if(device instanceof HeatingCoilDevice heater) {
            descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1),Map.of()));
            if(!heater.nativeBurned()) {
                var params=new HashMap<String,Double>();params.put("ambientOhms",(double)ModConfigs.server().heatingCoilResistance.getF());
                params.put("coefficientPerKelvin",0.0);params.put("heatCapacityJoulesPerKelvin",(double)ModConfigs.server().heatingCoilHeatCapacityJoulesPerKelvin.getF());
                params.put("conductanceWattsPerKelvin",ModConfigs.server().heatingCoilMaxPower.getF()/(600.0-22.0));params.put("ambientKelvin",295.15);
                boolean damage=ModConfigs.server().componentDamage.get();if(damage)params.put("tripKelvin",873.15);
                branches.add(new Branch(main,p0,p1,damage?"thermal_fuse":"thermal_resistor",params));
            }
        } else         if(device instanceof NativeLamp lamp) {
            descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1),Map.of()));
            main=branchId(device,"bulb/"+lamp.nativeBulbIdentity());
            if(lamp.hasBulb() && !lamp.isBurned()) {
                var properties=((ILightBulb)lamp.getBulbItem()).thermalProperties();
                double rise=properties.operatingTemperature()-LightFixtureDevice.AMBIENT_TEMPERATURE;
                var parameters=new HashMap<String,Double>();parameters.put("ambientOhms",(double)properties.minResistance());
                parameters.put("coefficientPerKelvin",(properties.maxResistance()/properties.minResistance()-1)/rise);
                parameters.put("heatCapacityJoulesPerKelvin",(double)ModConfigs.server().nativeLampHeatCapacityJoulesPerKelvin.getF());
                parameters.put("conductanceWattsPerKelvin",properties.ratedPower()/rise);
                parameters.put("ambientKelvin",273.15+LightFixtureDevice.AMBIENT_TEMPERATURE);
                boolean damage=ModConfigs.server().componentDamage.get();
                if(damage)parameters.put("tripKelvin",273.15+properties.overheatTemperature());
                branches.add(new Branch(main,p0,p1,damage?"thermal_fuse":"thermal_resistor",parameters));
            }

        } else {return false;}
        context.ports().put(new InWorldNode(0,device.pos),p0);context.ports().put(new InWorldNode(1,device.pos),p1);
        context.devicesByBranch().put(main,device);
        return true;
    }

    private static Map<String, Double> ratedResistance(double ohms, double watts) {
        var config = ModConfigs.server();
        double trip = config.nativeResistorTripKelvin.getF();
        return thermal(ohms, config.nativeResistorHeatCapacityJoulesPerKelvin.getF(), watts / (trip - 293.15), trip);
    }
}
