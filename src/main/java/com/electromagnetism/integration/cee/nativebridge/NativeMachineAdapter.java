package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.*;
import com.electromagnetism.config.ModConfigs;
import com.george_vi.electroenergetics.devices.device.SimulatedDevice;
import com.george_vi.electroenergetics.foundation.nodes.*;
import com.george_vi.electroenergetics.simulation.infrastructure.*;
import java.util.*;
import static com.electromagnetism.simulation.SimulationProtocol.*;

import static com.electromagnetism.integration.cee.nativebridge.CeeNativeWorld.branchId;

import static com.electromagnetism.integration.cee.nativebridge.NativeModelParameters.*;

final class NativeMachineAdapter implements NativeDeviceAdapter {
    @Override public boolean capture(NativeCaptureContext context,SimulatedDevice device,DeviceId id) {
        var infrastructure=context.infrastructure();
        var alternators=context.alternators();
        var devicesByBranch=context.devicesByBranch();
        var damageAssemblies=context.damageAssemblies();
        var descriptors=context.descriptors();
        var branches=context.branches();
        var ports=context.ports();
        var identities=context.identities();
        UUID main=branchId(device,"main");
        var p0=new PortId(id,0);var p1=new PortId(id,1);var internal=new PortId(id,2);
        if(device instanceof com.electromagnetism.content.electromechanics.excitation.ExcitationStatorDevice stator) {
            var field=new PortId(id,3);descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1,internal,field),Map.of()));
            branches.add(new Branch(main,p0,internal,"thermal_fuse",thermal(ModConfigs.server().excitationStatorResistance.getF(),100,1,393.15)));
            branches.add(new Branch(branchId(device,"field-flux"),internal,field,"inductor",Map.of("inductanceHenries",1.0)));devicesByBranch.put(main,device);
        } else         if(device instanceof com.george_vi.electroenergetics.content.transmission_distribution.voltage_regulator.VoltageRegulatorDevice regulator) {
            NativeRegulators.capture(infrastructure,regulator,id,descriptors,branches,ports);devicesByBranch.put(main,device);return true;
        } else         if(device instanceof com.george_vi.electroenergetics.content.rotor.AlternatorBrushesDevice || device instanceof com.george_vi.electroenergetics.content.rotor.ThreePhaseAlternatorBrushesDevice) {
            alternators.capture(infrastructure,device,id,descriptors,branches,ports,identities,devicesByBranch);return true;
        } else         if(device instanceof com.george_vi.electroenergetics.content.electric_motor.ElectricMotorDevice) {
            if(!(infrastructure.level.getBlockEntity(device.pos) instanceof NativeMotorBoundary motor))throw new IllegalArgumentException("Missing motor shaft boundary");
            var winding=new PortId(id,3);
            descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1,internal,winding),Map.of()));
            branches.add(new Branch(main,internal,p1,"shaft_boundary",Map.of("newtonMetresPerAmpere",(double)ModConfigs.server().nativeMotorTorqueConstant.getF(),"radiansPerSecond",motor.electromagnetism$shaftSpeedRpm()*Math.PI/30)));
            branches.add(new Branch(branchId(device,"motor/copper"),p0,winding,"thermal_fuse",thermal(ModConfigs.server().nativeMotorWindingOhms.getF(),100,1,393.15)));
            branches.add(new Branch(branchId(device,"motor/flux"),winding,internal,"inductor",Map.of("inductanceHenries",(double)ModConfigs.server().nativeMotorWindingHenries.getF())));
            devicesByBranch.put(branchId(device,"motor/copper"),device);devicesByBranch.put(main,device);
        } else         if(device instanceof com.george_vi.electroenergetics.content.electric_pump.ElectricPumpDevice) {
            if(!(infrastructure.level.getBlockEntity(device.pos) instanceof NativePumpBoundary pump))throw new IllegalArgumentException("Missing pump boundary");
            if(!pump.electromagnetism$pump().problem().isEmpty())throw new IllegalArgumentException(pump.electromagnetism$pump().problem());
            var returnPort=new PortId(id,3);var rectifier=new PortId(id,4);
            descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1,internal,returnPort,rectifier),Map.of()));
            branches.add(new Branch(NativeFluidPump.BranchRole.BUFFER.id(device),internal,returnPort,"capacitor",Map.of("capacitanceFarads",2*10000.0/(48*48))));
            branches.add(new Branch(NativeFluidPump.BranchRole.LIMIT.id(device),rectifier,internal,"battery_contact",Map.of("resistanceOhms",1.0,"fullVolts",48.0,"taperVolts",2.4),List.of(returnPort)));
            // Quasistatic rectifier diodes: independent ideal junction-charge
            // constraints around a bridge form a redundant initialization loop.
            // Energy storage belongs to the explicit DC-link capacitor.
            Map<String,Double> diode=Map.of("saturationAmperes",1e-9,"thermalVolts",0.05,"junctionFarads",0.0);
            branches.add(new Branch(NativeFluidPump.BranchRole.MAIN.id(device),p0,rectifier,"diode",diode));branches.add(new Branch(NativeFluidPump.BranchRole.RECTIFIER_1.id(device),p1,rectifier,"diode",diode));
            branches.add(new Branch(NativeFluidPump.BranchRole.RECTIFIER_2.id(device),returnPort,p0,"diode",diode));branches.add(new Branch(NativeFluidPump.BranchRole.RECTIFIER_3.id(device),returnPort,p1,"diode",diode));
            devicesByBranch.put(main,device);
        } else         if(device instanceof com.george_vi.electroenergetics.content.transmission_distribution.current_transformer.CurrentTransformerDevice transformer) {
            var terminals=java.util.stream.IntStream.range(0,6).mapToObj(n->new PortId(id,n)).toList();
            descriptors.add(new DeviceInput(id,"ports",terminals,Map.of()));
            for(int n=0;n<4;n++)ports.put(new InWorldNode(n,device.pos),terminals.get(n));
            if(!(infrastructure.level.getBlockState(device.pos).getBlock() instanceof com.george_vi.electroenergetics.content.transmission_distribution.current_transformer.CurrentTransformerBlock))
                throw new IllegalArgumentException("Current transformer owner has no matching world block at "+device.pos);
            if(!(infrastructure.deviceSD.getDevice(device.pos.below()) instanceof com.george_vi.electroenergetics.content.transmission_distribution.current_transformer.CurrentTransformerDevice)) {
                var top=transformer;
                var assembly=new ArrayList<SimulatedDevice>();assembly.add(device);
                for(int y=device.pos.getY()+1;y<infrastructure.level.getMaxBuildHeight();y++) {
                    var next=infrastructure.deviceSD.getDevice(new net.minecraft.core.BlockPos(device.pos.getX(),y,device.pos.getZ()));
                    if(!(next instanceof com.george_vi.electroenergetics.content.transmission_distribution.current_transformer.CurrentTransformerDevice part))break;
                    if(!(infrastructure.level.getBlockState(part.pos).getBlock() instanceof com.george_vi.electroenergetics.content.transmission_distribution.current_transformer.CurrentTransformerBlock))
                        throw new IllegalArgumentException("Current transformer member world owner missing at "+part.pos);
                    assembly.add(part);top=part;
                }
                damageAssemblies.put(device,List.copyOf(assembly));
                double ratio=top.ratio;
                if(!Double.isFinite(ratio) || ratio<=0)throw new IllegalArgumentException("Current transformer requires positive Np/Ns turns ratio at "+top.pos);
                var topId=((CeeDeviceIdentity)top).electromagnetism$nativeIdentity();
                var primaryDot=new PortId(topId,0);var primaryReturn=new PortId(topId,1);
                var config=ModConfigs.server();double lm=config.nativeTransformerMagnetizingHenries.getF(),leak=config.nativeTransformerLeakageHenries.getF();
                branches.add(new Branch(main,terminals.get(4),primaryReturn,"coupled_windings",Map.of("primaryHenries",lm+leak,"secondaryHenries",lm/(ratio*ratio)+leak,"mutualHenries",lm/ratio,"primaryOhms",0.0,"secondaryOhms",0.0),List.of(terminals.get(5),terminals.get(3))));
                var copper=thermal(config.nativeTransformerWindingOhms.getF(),config.nativeTransformerHeatCapacityJoulesPerKelvin.getF(),config.nativeTransformerCoolingWattsPerKelvin.getF(),393.15);
                UUID primaryHeat=branchId(device,"winding-heat/0"),secondaryHeat=branchId(device,"winding-heat/1");
                devicesByBranch.put(primaryHeat,device);devicesByBranch.put(secondaryHeat,device);
                branches.add(new Branch(primaryHeat,primaryDot,terminals.get(4),"thermal_fuse",copper));
                branches.add(new Branch(secondaryHeat,terminals.get(5),terminals.get(2),"thermal_fuse",copper));
            }
        } else         if(device instanceof com.george_vi.electroenergetics.content.variac.VariacDevice variac) {
            double gain=variac.ratio*variac.progress;
            if(!Double.isFinite(variac.ratio) || variac.ratio<=0 || !Float.isFinite(variac.progress) || variac.progress<0 || variac.progress>1 || !Double.isFinite(gain))
                throw new IllegalArgumentException("Variac requires positive turns gain and tap position in [0,1] at "+device.pos);
            var terminals=java.util.stream.IntStream.range(0,5).mapToObj(n->new PortId(id,n)).toList();
            descriptors.add(new DeviceInput(id,"ports",terminals,Map.of()));
            for(int n=0;n<3;n++)ports.put(new InWorldNode(n,device.pos),terminals.get(n));
            var config=ModConfigs.server();double lm=config.nativeTransformerMagnetizingHenries.getF(),leak=config.nativeTransformerLeakageHenries.getF();
            // Three-terminal, linear magnetic model. The finite leakage branch remains
            // at zero tap; changing the tap preserves flux and books external work in Rust.
            branches.add(new Branch(main,terminals.get(3),p1,"coupled_windings",Map.of("primaryHenries",lm+leak,"secondaryHenries",lm*gain*gain+leak,"mutualHenries",lm*gain,"primaryOhms",0.0,"secondaryOhms",0.0),List.of(terminals.get(4),p1)));
            var copper=thermal(config.nativeTransformerWindingOhms.getF(),config.nativeTransformerHeatCapacityJoulesPerKelvin.getF(),config.nativeTransformerCoolingWattsPerKelvin.getF()*(variac.oilLogged?2:1),393.15);
            for(int winding=0;winding<2;winding++) {
                UUID heat=branchId(device,"winding-heat/"+winding);devicesByBranch.put(heat,device);
                branches.add(new Branch(heat,terminals.get(winding==0?0:4),terminals.get(winding==0?3:2),"thermal_fuse",copper));
            }
        } else         if(device instanceof com.george_vi.electroenergetics.content.transmission_distribution.transformer.TransformerDevice
                || device instanceof com.george_vi.electroenergetics.content.transmission_distribution.transformer.TransformerCoreDevice) {
            var terminals=new ArrayList<>(java.util.stream.IntStream.range(0,8).mapToObj(n->new PortId(id,n)).toList());
            descriptors.add(new DeviceInput(id,"ports",terminals,Map.of()));
            boolean core=device instanceof com.george_vi.electroenergetics.content.transmission_distribution.transformer.TransformerCoreDevice;
            for(int n=0;n<(core?2:4);n++)ports.put(new InWorldNode(n,device.pos),terminals.get(n));
            double ratio;
            if(core) {
                var state=infrastructure.level.getBlockState(device.pos);
                if(!(state.getBlock() instanceof com.george_vi.electroenergetics.content.transmission_distribution.transformer.TransformerCoreBlock))throw new IllegalArgumentException("Transformer core world owner missing at "+device.pos);
                var facing=state.getValue(com.george_vi.electroenergetics.content.transmission_distribution.transformer.TransformerCoreBlock.FACING);
                var partner=infrastructure.deviceSD.getDevice(device.pos.relative(facing));
                var other=infrastructure.level.getBlockState(device.pos.relative(facing));
                if(!(partner instanceof com.george_vi.electroenergetics.content.transmission_distribution.transformer.TransformerCoreDevice)
                        || other.getBlock()!=state.getBlock() || other.getValue(com.george_vi.electroenergetics.content.transmission_distribution.transformer.TransformerCoreBlock.FACING)!=facing.getOpposite()) {
                    // A half-built assembly still owns a physical winding. Preserve its
                    // terminals and model copper/flux locally until a partner is present.
                    // Ordinary topology transactions account for stored energy on pairing.
                    var config=ModConfigs.server();
                    // The paired main branch is a coupled winding, not this copper body.
                    main=branchId(device,"unpaired-copper");
                    branches.add(new Branch(main,p0,internal,"thermal_fuse",thermal(
                            config.nativeTransformerWindingOhms.getF(),config.nativeTransformerHeatCapacityJoulesPerKelvin.getF(),
                            NativeTransformerGeometry.coolingPerWinding(infrastructure.level,device.pos),393.15)));
                    branches.add(new Branch(branchId(device,"unpaired-flux"),internal,p1,"inductor",
                            Map.of("inductanceHenries",(double)(config.nativeTransformerMagnetizingHenries.getF()+config.nativeTransformerLeakageHenries.getF()))));
                    branches.add(new Branch(branchId(device,"unpaired-insulation"),p0,p1,"resistor",Map.of("resistanceOhms",1e9)));
                    devicesByBranch.put(main,device);
                    return true;
                }
                if(facing.getAxisDirection()==net.minecraft.core.Direction.AxisDirection.NEGATIVE)return true;
                damageAssemblies.put(device,List.of(device,partner));
                var partnerId=((CeeDeviceIdentity)partner).electromagnetism$nativeIdentity();
                terminals.set(2,new PortId(partnerId,0));terminals.set(3,new PortId(partnerId,1));
                // Turns are the explicit user input; the upstream zero sentinel means 1:1.
                ratio=((com.george_vi.electroenergetics.content.transmission_distribution.transformer.TransformerCoreDevice)device).ratio;
                if(ratio==0)ratio=1;
            } else ratio=((com.george_vi.electroenergetics.content.transmission_distribution.transformer.TransformerDevice)device).ratio;
            if(!Double.isFinite(ratio) || ratio<=0)throw new IllegalArgumentException("Transformer requires positive Np/Ns turns ratio at "+device.pos);
            var config=ModConfigs.server();double lm=config.nativeTransformerMagnetizingHenries.getF(),leak=config.nativeTransformerLeakageHenries.getF();
            branches.add(new Branch(main,terminals.get(5),p1,"coupled_windings",Map.of("primaryHenries",lm+leak,"secondaryHenries",lm/(ratio*ratio)+leak,"mutualHenries",lm/ratio,"primaryOhms",0.0,"secondaryOhms",0.0),List.of(terminals.get(6),terminals.get(3))));
            var copper=thermal(config.nativeTransformerWindingOhms.getF(),config.nativeTransformerHeatCapacityJoulesPerKelvin.getF(),(core?NativeTransformerGeometry.coolingPerWinding(infrastructure.level,device.pos):config.nativeTransformerCoolingWattsPerKelvin.getF()),393.15);
            for(int winding=0;winding<2;winding++) {
                UUID heat=branchId(device,"winding-heat/"+winding);devicesByBranch.put(heat,device);
                branches.add(new Branch(heat,terminals.get(winding==0?4:6),terminals.get(winding==0?5:7),"thermal_fuse",copper));
            }
            // Both voltage probes have finite 1 Gohm input resistance. The primary
            // probe also defines the unpowered terminal voltage when flux is held
            // across an accepted topology boundary (an ideal open winding cannot).
            branches.add(new Branch(branchId(device,"primary-voltage"),p0,p1,"resistor",Map.of("resistanceOhms",1e9)));
            branches.add(new Branch(branchId(device,"primary-meter"),p0,p1,"wattmeter_contact",Map.of("resistanceOhms",0.001),List.of(terminals.get(4))));
            branches.add(new Branch(branchId(device,"secondary-meter"),terminals.get(7),terminals.get(3),"wattmeter_contact",Map.of("resistanceOhms",0.001),List.of(terminals.get(2))));
            branches.add(new Branch(branchId(device,"secondary-voltage"),terminals.get(2),terminals.get(3),"resistor",Map.of("resistanceOhms",1e9)));
        } else {return false;}
        context.ports().put(new InWorldNode(0,device.pos),p0);context.ports().put(new InWorldNode(1,device.pos),p1);
        context.devicesByBranch().put(main,device);
        return true;
    }
}
