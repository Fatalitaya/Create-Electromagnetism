package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.config.ModConfigs;
import com.electromagnetism.simulation.SimulationProtocol.*;
import com.electromagnetism.simulation.WorldRecipients;
import com.electromagnetism.simulation.SimulationCoordinator;
import com.george_vi.electroenergetics.content.rotor.*;
import com.george_vi.electroenergetics.devices.device.SimulatedDevice;
import com.george_vi.electroenergetics.foundation.nodes.InWorldNode;
import com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Assembly ownership uses a persisted rotor instance, never the index of a matrix row. */
public final class NativeAlternators {
    private record Assembly(UUID identity,UUID anchor,List<AlternatorRotorBlockEntity> rotors,List<UUID> phases){}
    private final NativeAssemblyIdentities identities=new NativeAssemblyIdentities();
    private Map<UUID,WorldRecipients.Assembly> groups=Map.of();
    private final List<Assembly> assemblies=new ArrayList<>();
    private final Set<NativeBrushAssembly> refreshedBrushes=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<com.electromagnetism.content.electromechanics.excitation.ExcitationStatorDevice,PortId> fieldReturns=new IdentityHashMap<>();
    private final Map<com.electromagnetism.content.electromechanics.excitation.ExcitationStatorDevice,UUID> fieldOwners=new IdentityHashMap<>();
    private final Set<AlternatorRotorBlockEntity> lastRotors=Collections.newSetFromMap(new IdentityHashMap<>());
    public void restore(List<WorldRecipients.Assembly> saved){identities.restore(saved);}
    public void restoreIdentities(net.minecraft.nbt.ListTag saved){identities.restore(saved);}
    public net.minecraft.nbt.ListTag saveIdentities(){return identities.save();}
    public void completeCapture(){identities.capture(recipients());}
    static boolean isBrushDevice(SimulatedDevice device) {
        return device instanceof AlternatorBrushesDevice || device instanceof ThreePhaseAlternatorBrushesDevice;
    }

    public void begin(InfrastructureSavedData sd){
        begin(sd,sd.deviceSD.getDevices());
    }
    void begin(InfrastructureSavedData sd,Collection<SimulatedDevice> registeredDevices){
        assemblies.clear();fieldReturns.clear();fieldOwners.clear();groups=Map.of();
        refreshedBrushes.clear();
        var components=new ArrayList<List<UUID>>();
        for(var device:registeredDevices) {
            if (!isBrushDevice(device) || !(sd.level.getBlockEntity(device.pos) instanceof NativeBrushAssembly brush)) continue;
            try {brush.electromagnetism$refreshAssembly();}catch(IllegalArgumentException | IllegalStateException invalid){continue;}
            refreshedBrushes.add(brush);
            var members=brush.electromagnetism$rotors().stream().map(r->((NativeRotorBoundary)r).electromagnetism$rotorIdentity()).sorted().toList();
            if(!members.isEmpty() && !components.contains(members))components.add(members);
        }
        groups=identities.plan(components);
    }
    public List<WorldRecipients.Assembly> recipients(){
        var result=assemblies.stream().map(a->new WorldRecipients.Assembly(a.identity(),a.anchor(),
                a.rotors().stream().map(r->((NativeRotorBoundary)r).electromagnetism$rotorIdentity()).sorted().toList(),a.phases(),List.of())).toList();
        return result;
    }
    public void locate(Map<DeviceId,Long> positions,Map<DeviceId,Long> networks){
        for(var a:assemblies)for(var rotor:a.rotors()) {
            var id=new DeviceId(((NativeRotorBoundary)rotor).electromagnetism$rotorIdentity(),0);
            positions.put(id,rotor.getBlockPos().asLong());
            if(rotor.hasNetwork())networks.put(id,rotor.getOrCreateNetwork().id);
        }
    }
    private static UUID branch(UUID owner,String role){return UUID.nameUUIDFromBytes((owner+"/"+role).getBytes(StandardCharsets.UTF_8));}
    public void capture(InfrastructureSavedData sd,SimulatedDevice device,DeviceId id,List<DeviceInput> devices,List<Branch> branches,Map<InWorldNode,PortId> ports,Set<DeviceId> identities,Map<UUID,SimulatedDevice> consumers){
        if(!(sd.level.getBlockEntity(device.pos) instanceof NativeBrushAssembly brush))throw new IllegalArgumentException("Missing alternator assembly boundary");
        if (!refreshedBrushes.contains(brush)) brush.electromagnetism$refreshAssembly();
        if(!brush.electromagnetism$assemblyProblem().isEmpty())throw new IllegalArgumentException(brush.electromagnetism$assemblyProblem());
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
        var group=groups.get(canonical.electromagnetism$rotorIdentity());
        if(group==null)throw new IllegalArgumentException("Missing assembly identity candidate");
        UUID rotor=group.identity();var rotorId=new DeviceId(rotor,0);
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
            UUID source=branch(rotor,"phase/"+phase+(phases==1?"/ac-shaft":"/shaft"));phaseBranches.add(source);
            PortId armatureReturn=terminals.get(0);
            for(int fieldIndex=0;fieldIndex<fields.size();fieldIndex++) {
                var field=fields.get(fieldIndex);var fieldId=((CeeDeviceIdentity)field).electromagnetism$nativeIdentity();
                var fieldInput=fieldReturns.getOrDefault(field,new PortId(fieldId,3));
                UUID induced=branch(rotor,"phase/"+phase+(phases==1?"/ac-field/":"/field/")+fieldId.instance());phaseBranches.add(induced);
                int terminal=100+2*(induced.hashCode()&0x1fffffff);
                var fieldOutput=new PortId(rotorId,terminal);var armature=new PortId(rotorId,terminal+1);
                if(internals.contains(fieldOutput)||internals.contains(armature))throw new IllegalArgumentException("Excitation terminal identity collision");
                internals.add(fieldOutput);internals.add(armature);
                var parameters=new HashMap<String,Double>();
                double fieldCoupling=(double)ModConfigs.server().nativeMotorTorqueConstant.getF()*ModConfigs.server().excitationStatorFieldFactor.getF();
                parameters.put("newtonMetresPerAmpereSquared",fieldCoupling);
                parameters.put("radiansPerSecond",omega);
                parameters.put("polePairs",1.0);parameters.put("phaseRadians",-phase*2*Math.PI/phases);
                branches.add(new Branch(induced,armature,armatureReturn,"field_ac_shaft",parameters,List.of(fieldInput,fieldOutput)));
                fieldReturns.put(field,fieldOutput);armatureReturn=armature;
            }
            // One pole pair: ordinary brushes expose one sine phase; three-phase brushes are 120 degrees apart.
            branches.add(new Branch(source,a,armatureReturn,"ac_shaft",Map.of("newtonMetresPerAmpere",k,"radiansPerSecond",omega,"polePairs",1.0,"phaseRadians",-phase*2*Math.PI/phases)));
            branches.add(new Branch(branch(rotor,"phase/"+phase+"/flux"),b,a,"inductor",Map.of("inductanceHenries",(double)ModConfigs.server().nativeMotorWindingHenries.getF())));
            branches.add(new Branch(branch(rotor,"phase/"+phase+"/copper"),terminals.get(phase+1),b,"thermal_fuse",Map.of("ambientOhms",(double)ModConfigs.server().nativeMotorWindingOhms.getF(),"coefficientPerKelvin",0.0039,"heatCapacityJoulesPerKelvin",100.0,"conductanceWattsPerKelvin",1.0,"ambientKelvin",293.15,"tripKelvin",393.15)));
            consumers.put(branch(rotor,"phase/"+phase+"/copper"),device);
        }
        devices.add(new DeviceInput(rotorId,"ports",internals,Map.of()));
        assemblies.add(new Assembly(rotor,group.anchor(),rotors,List.copyOf(phaseBranches)));
    }
    public void finishFields(InfrastructureSavedData sd,List<Branch> branches){
        finishFields(sd.deviceSD.getDevices(),branches);
    }
    void finishFields(Collection<SimulatedDevice> registeredDevices,List<Branch> branches){
        for(var device:registeredDevices)if(device instanceof com.electromagnetism.content.electromechanics.excitation.ExcitationStatorDevice field) {
            var id=((CeeDeviceIdentity)field).electromagnetism$nativeIdentity();var end=fieldReturns.getOrDefault(field,new PortId(id,3));
            branches.add(new Branch(CeeNativeWorld.branchId(field,"field-return"),end,new PortId(id,1),"dc_voltage_source",Map.of("voltageVolts",0.0)));
        }
    }
    public void apply(InfrastructureSavedData sd,SimulationCoordinator.Frame historical,SimulationCoordinator.Frame live,Map<UUID,Reading> readings){
        for(var assembly:historical.recipients().assemblies()) {
            if(assembly.phases().stream().anyMatch(id->!SimulationCoordinator.currentMechanicalBranch(live,historical,id)))continue;
            double torque=0;boolean valid=true;
            for(UUID phase:assembly.phases()){var value=readings.get(phase);if(value==null){valid=false;break;}torque+=value.torqueNewtonMetres().orElse(0);}
            for(UUID member:assembly.members()) {
                Long position=historical.recipients().positions().get(new DeviceId(member,0));if(position==null)continue;
                if(sd.level.getBlockEntity(net.minecraft.core.BlockPos.of(position)) instanceof AlternatorRotorBlockEntity rotor
                        && ((NativeRotorBoundary)rotor).electromagnetism$rotorIdentity().equals(member)) {
                    lastRotors.add(rotor);((NativeRotorBoundary)rotor).electromagnetism$torque(torque/assembly.members().size(),valid);
                }
            }
        }
    }
    public void revokeChanged(InfrastructureSavedData sd,SimulationCoordinator.Frame before,SimulationCoordinator.Frame after) {
        if(before==null)return;
        for(var assembly:before.recipients().assemblies()) {
            var current=after.recipients().assemblies().stream().filter(a->a.identity().equals(assembly.identity())).findFirst();
            boolean revoked=current.isEmpty() || !current.get().members().equals(assembly.members());
            for(UUID member:assembly.members()) {
                var id=new DeviceId(member,0);Long position=before.recipients().positions().get(id);
                if(position==null)continue;
                var pos=net.minecraft.core.BlockPos.of(position);
                boolean changed=revoked || !sd.level.isLoaded(pos) || !Objects.equals(before.recipients().mechanicalNetworks().get(id),after.recipients().mechanicalNetworks().get(id));
                if(sd.level.getBlockEntity(pos) instanceof NativeRotorBoundary rotor && rotor.electromagnetism$rotorIdentity().equals(member) && changed)rotor.electromagnetism$torque(0,false);
            }
        }
    }
    public void invalidate(){for(var rotor:lastRotors)if(!rotor.isRemoved())((NativeRotorBoundary)rotor).electromagnetism$torque(0,false);}
}
