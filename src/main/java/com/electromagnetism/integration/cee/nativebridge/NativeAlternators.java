package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.config.ModConfigs;
import com.electromagnetism.simulation.SimulationProtocol.*;
import com.george_vi.electroenergetics.content.rotor.*;
import com.george_vi.electroenergetics.devices.device.SimulatedDevice;
import com.george_vi.electroenergetics.foundation.nodes.InWorldNode;
import com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Assembly ownership uses a persisted rotor instance, never the index of a matrix row. */
public final class NativeAlternators {
    private record Assembly(List<AlternatorRotorBlockEntity> rotors,List<UUID> phases,List<NativeEquipmentDisplay> displays){}
    private final Set<NativeEquipmentDisplay> lastDisplays=Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<Assembly> assemblies=new ArrayList<>();
    private final Map<com.electromagnetism.content.electromechanics.excitation.ExcitationStatorDevice,PortId> fieldReturns=new IdentityHashMap<>();
    private final Map<com.electromagnetism.content.electromechanics.excitation.ExcitationStatorDevice,UUID> fieldOwners=new IdentityHashMap<>();
    private final Set<AlternatorRotorBlockEntity> lastRotors=Collections.newSetFromMap(new IdentityHashMap<>());
    public void begin(){assemblies.clear();fieldReturns.clear();fieldOwners.clear();}
    private static UUID branch(UUID owner,String role){return UUID.nameUUIDFromBytes((owner+"/"+role).getBytes(StandardCharsets.UTF_8));}
    public void capture(InfrastructureSavedData sd,SimulatedDevice device,DeviceId id,List<DeviceInput> devices,List<Branch> branches,Map<InWorldNode,PortId> ports,Set<DeviceId> identities,Map<UUID,SimulatedDevice> consumers){
        if(!(sd.level.getBlockEntity(device.pos) instanceof NativeBrushAssembly brush))throw new IllegalArgumentException("Missing alternator assembly boundary");
        brush.electromagnetism$refreshAssembly();if(!brush.electromagnetism$assemblyProblem().isEmpty())throw new IllegalArgumentException(brush.electromagnetism$assemblyProblem());
        int phases=device instanceof ThreePhaseAlternatorBrushesDevice?3:1;
        var terminals=new ArrayList<PortId>();
        for(int i=0;i<=phases;i++){var port=new PortId(id,i);terminals.add(port);ports.put(new InWorldNode(i,device.pos),port);}
        devices.add(new DeviceInput(id,"ports",terminals,Map.of()));
        var other=brush.electromagnetism$otherBrush();
        if(other!=null && other.compareTo(device.pos)<0) {
            var owner=sd.deviceSD.getDevice(other);
            if(owner==null || owner.type!=device.type)throw new IllegalArgumentException("Missing paired brush owner");
            var otherId=((CeeDeviceIdentity)owner).electromagnetism$nativeIdentity();
            for(int i=0;i<terminals.size();i++)branches.add(new Branch(CeeNativeWorld.branchId(device,"paired-brush/"+i),terminals.get(i),new PortId(otherId,i),"resistor",Map.of("resistanceOhms",0.05)));
            return;
        }
        var rotors=brush.electromagnetism$rotors().stream().sorted(Comparator.comparing(AlternatorRotorBlockEntity::getBlockPos)).toList();
        if(rotors.isEmpty())return;
        var canonical=(NativeRotorBoundary)rotors.getFirst();
        UUID rotor=canonical.electromagnetism$rotorIdentity();var rotorId=new DeviceId(rotor,0);
        if(!identities.add(rotorId))throw new IllegalArgumentException("Duplicate native rotor owner");
        double omega=canonical.electromagnetism$shaftSpeedRpm()*Math.PI/30,excitation=0;
        for(var be:rotors) {
            var boundary=(NativeRotorBoundary)be;
            if(!boundary.electromagnetism$rotorProblem().isEmpty())throw new IllegalArgumentException(boundary.electromagnetism$rotorProblem());
            if(be.isRemoved() || Math.abs(boundary.electromagnetism$shaftSpeedRpm()*Math.PI/30-omega)>1e-6)throw new IllegalArgumentException("Inconsistent rotor shaft speeds");
            excitation+=boundary.electromagnetism$permanentExcitation();
        }
        var internals=new ArrayList<PortId>();var phaseBranches=new ArrayList<UUID>();
        var fields=new ArrayList<com.electromagnetism.content.electromechanics.excitation.ExcitationStatorDevice>();
        for(var be:rotors)for(var direction:net.minecraft.core.Direction.values()) {
            if(direction.getAxis()==be.getBlockState().getValue(AlternatorRotorBlock.AXIS))continue;
            var part=sd.deviceSD.getDevice(be.getBlockPos().relative(direction));
            if(part instanceof com.electromagnetism.content.electromechanics.excitation.ExcitationStatorDevice field && !fields.contains(field)){
                if(!sd.level.isLoaded(field.pos))throw new IllegalArgumentException("Excitation stator owner unloaded");
                var state=sd.level.getBlockState(field.pos);
                if(!(state.getBlock() instanceof com.electromagnetism.content.electromechanics.excitation.ExcitationStatorBlock))throw new IllegalArgumentException("Excitation stator world owner missing");
                if(com.electromagnetism.content.electromechanics.excitation.ExcitationStatorBlock.canPowerRotor(field.pos,state,be.getBlockPos(),be.getBlockState()))fields.add(field);
            }
        }
        fields.sort(Comparator.comparing(field->((CeeDeviceIdentity)field).electromagnetism$nativeIdentity().instance()));
        for(var field:fields){var old=fieldOwners.put(field,rotor);if(old!=null&&!old.equals(rotor))throw new IllegalArgumentException("Excitation stator coupled to multiple rotor owners");}
        double k=ModConfigs.server().nativeMotorTorqueConstant.getF()*excitation;
        for(int phase=0;phase<phases;phase++) {
            var a=new PortId(rotorId,phase*3);var b=new PortId(rotorId,phase*3+1);var c=new PortId(rotorId,phase*3+2);internals.addAll(List.of(a,b,c));
            UUID source=branch(rotor,"phase/"+phase+"/shaft");phaseBranches.add(source);
            PortId armatureReturn=terminals.get(0);
            for(int fieldIndex=0;fieldIndex<fields.size();fieldIndex++) {
                var field=fields.get(fieldIndex);var fieldId=((CeeDeviceIdentity)field).electromagnetism$nativeIdentity();
                var fieldInput=fieldReturns.getOrDefault(field,new PortId(fieldId,3));
                UUID induced=branch(rotor,"phase/"+phase+"/field/"+fieldId.instance());phaseBranches.add(induced);
                int terminal=100+2*(induced.hashCode()&0x1fffffff);
                var fieldOutput=new PortId(rotorId,terminal);var armature=new PortId(rotorId,terminal+1);
                if(internals.contains(fieldOutput)||internals.contains(armature))throw new IllegalArgumentException("Excitation terminal identity collision");
                internals.add(fieldOutput);internals.add(armature);
                var parameters=new HashMap<String,Double>();parameters.put("newtonMetresPerAmpereSquared",(double)ModConfigs.server().nativeMotorTorqueConstant.getF());parameters.put("radiansPerSecond",omega);
                if(phases==3){parameters.put("polePairs",1.0);parameters.put("phaseRadians",-phase*2*Math.PI/3);}
                branches.add(new Branch(induced,armature,armatureReturn,phases==3?"field_ac_shaft":"field_shaft",parameters,List.of(fieldInput,fieldOutput)));
                fieldReturns.put(field,fieldOutput);armatureReturn=armature;
            }
            if(k>0) {
                if(phases==1)branches.add(new Branch(source,a,armatureReturn,"shaft_boundary",Map.of("newtonMetresPerAmpere",k,"radiansPerSecond",omega)));
                else branches.add(new Branch(source,a,armatureReturn,"ac_shaft",Map.of("newtonMetresPerAmpere",k,"radiansPerSecond",omega,"polePairs",1.0,"phaseRadians",-phase*2*Math.PI/3)));
            } else branches.add(new Branch(source,a,armatureReturn,"dc_voltage_source",Map.of("voltageVolts",0.0)));
            branches.add(new Branch(branch(rotor,"phase/"+phase+"/flux"),b,a,"inductor",Map.of("inductanceHenries",(double)ModConfigs.server().nativeMotorWindingHenries.getF())));
            branches.add(new Branch(branch(rotor,"phase/"+phase+"/copper"),terminals.get(phase+1),b,"thermal_fuse",Map.of("ambientOhms",(double)ModConfigs.server().nativeMotorWindingOhms.getF(),"coefficientPerKelvin",0.0039,"heatCapacityJoulesPerKelvin",100.0,"conductanceWattsPerKelvin",1.0,"ambientKelvin",293.15,"tripKelvin",393.15)));
            consumers.put(branch(rotor,"phase/"+phase+"/copper"),device);
        }
        devices.add(new DeviceInput(rotorId,"ports",internals,Map.of()));
        var displays=new ArrayList<NativeEquipmentDisplay>();
        if(brush instanceof NativeEquipmentDisplay display)displays.add(display);
        if(other!=null && sd.level.getBlockEntity(other) instanceof NativeEquipmentDisplay display)displays.add(display);
        assemblies.add(new Assembly(rotors,phaseBranches,displays));
    }
    public void finishFields(InfrastructureSavedData sd,List<Branch> branches){
        for(var device:sd.deviceSD.getDevices())if(device instanceof com.electromagnetism.content.electromechanics.excitation.ExcitationStatorDevice field) {
            var id=((CeeDeviceIdentity)field).electromagnetism$nativeIdentity();var end=fieldReturns.getOrDefault(field,new PortId(id,3));
            branches.add(new Branch(CeeNativeWorld.branchId(field,"field-return"),end,new PortId(id,1),"dc_voltage_source",Map.of("voltageVolts",0.0)));
        }
    }
    public void apply(Map<UUID,Reading> readings,double start,double end){
        var displays=Collections.newSetFromMap(new IdentityHashMap<NativeEquipmentDisplay,Boolean>());
        var current=Collections.newSetFromMap(new IdentityHashMap<AlternatorRotorBlockEntity,Boolean>());
        for(var assembly:assemblies) {
            double torque=0;boolean valid=true;
            for(var phase:assembly.phases()){var value=readings.get(phase);if(value==null){valid=false;break;}torque+=value.torqueNewtonMetres().orElse(0);}
            for(var display:assembly.displays()){displays.add(display);if(valid)display.electromagnetism$display(List.of(new NativeEquipmentDisplay.Value("electromagnetism.measurement.shaft_torque",torque,"N m")),start,end);else display.electromagnetism$displayUnavailable();}
            for(var rotor:assembly.rotors()){current.add(rotor);((NativeRotorBoundary)rotor).electromagnetism$torque(torque/assembly.rotors().size(),valid);}
        }
        for(var rotor:lastRotors)if(!current.contains(rotor) && !rotor.isRemoved())((NativeRotorBoundary)rotor).electromagnetism$torque(0,false);
        lastRotors.clear();lastRotors.addAll(current);
        for(var display:lastDisplays)if(!displays.contains(display))display.electromagnetism$displayUnavailable();lastDisplays.clear();lastDisplays.addAll(displays);
    }
    public void invalidate(){for(var display:lastDisplays)display.electromagnetism$displayUnavailable();for(var rotor:lastRotors)if(!rotor.isRemoved())((NativeRotorBoundary)rotor).electromagnetism$torque(0,false);}
}
