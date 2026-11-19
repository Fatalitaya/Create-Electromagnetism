package com.electromagnetism.integration.cee.nativebridge;
import com.electromagnetism.simulation.SimulationProtocol.*;
import com.george_vi.electroenergetics.config.CEEConfigs;
import com.george_vi.electroenergetics.content.transmission_distribution.voltage_regulator.VoltageRegulatorDevice;
import com.george_vi.electroenergetics.foundation.nodes.InWorldNode;
import com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData;
import java.util.*;
public final class NativeRegulators {
    private NativeRegulators(){}
    private static PortId port(InfrastructureSavedData sd,net.minecraft.core.BlockPos position,int index) {
        var device=sd.deviceSD.getDevice(position);
        if(device==null || !(device instanceof CeeDeviceIdentity identity))throw new IllegalArgumentException("Regulator terminal owner missing at "+position);
        return new PortId(identity.electromagnetism$nativeIdentity(),index);
    }
    public static void capture(InfrastructureSavedData sd,VoltageRegulatorDevice device,DeviceId id,List<DeviceInput> devices,List<Branch> branches,Map<InWorldNode,PortId> ports) {
        var declared=new ArrayList<PortId>();
        for(var node:sd.getNodes())if(node.sourcePos().equals(device.pos)){var p=new PortId(id,node.id());ports.put(node,p);declared.add(p);}
        if(!device.bottom){devices.add(new DeviceInput(id,"ports",declared,Map.of()));return;}
        if(!Double.isFinite(device.targetVoltage)||device.targetVoltage<=0)throw new IllegalArgumentException("Regulator target voltage must be positive");
        int length=0;VoltageRegulatorDevice top=device;
        while(!top.top) {
            length++;if(length>256 || !sd.level.isLoaded(device.pos.above(length)))throw new IllegalArgumentException("Regulator assembly exceeds loaded bounds");
            top=sd.deviceSD.getDevice(device.pos.above(length),VoltageRegulatorDevice.class);
            if(top==null)throw new IllegalArgumentException("Regulator pole is incomplete");
        }
        var upper=device.pos.above(length+(top.sliced==0?0:1));
        PortId input=port(sd,upper,top.sliced==2?1:0),output=port(sd,upper,top.sliced==2?0:1);
        int returnPin = length == 0 && top.sliced == 0 ? 2 : 0;
        var ground = port(sd, device.pos, returnPin);
        PortId copper=new PortId(id,100),regulated=new PortId(id,101),flux=new PortId(id,102);
        declared.addAll(List.of(copper,regulated,flux));devices.add(new DeviceInput(id,"ports",declared,Map.of()));
        branches.add(new Branch(CeeNativeWorld.branchId(device,"main"),copper,ground,"voltage_regulator",Map.of("targetVolts",device.targetVoltage,"responseSeconds",0.5,"minimumRatio",0.8,"maximumRatio",1.2),List.of(regulated)));
        branches.add(new Branch(CeeNativeWorld.branchId(device,"regulator/input-power"),input,ground,"wattmeter_contact",
                Map.of("resistanceOhms",0.01),List.of(copper)));
        branches.add(new Branch(CeeNativeWorld.branchId(device,"regulator/output-copper"),regulated,flux,"resistor",Map.of("resistanceOhms",0.01)));
        branches.add(new Branch(CeeNativeWorld.branchId(device,"regulator/flux"),flux,output,"inductor",Map.of("inductanceHenries",0.001)));
        branches.add(new Branch(CeeNativeWorld.branchId(device,"regulator/input-voltage"),input,ground,"resistor",Map.of("resistanceOhms",1e9)));
        branches.add(new Branch(CeeNativeWorld.branchId(device,"regulator/output-voltage"),output,ground,"resistor",Map.of("resistanceOhms",1e9)));
        double returnOhms = CEEConfigs.server().resistanceValues.wireResistance.get();
        branches.add(new Branch(CeeNativeWorld.branchId(device, "regulator/return"),
                ground, port(sd, device.pos, returnPin + 1), "resistor", Map.of("resistanceOhms", returnOhms)));
    }
}
