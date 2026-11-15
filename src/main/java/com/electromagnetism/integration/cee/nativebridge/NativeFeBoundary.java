package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.config.ModConfigs;
import com.george_vi.electroenergetics.content.converter.ConverterBlockEntity;
import com.george_vi.electroenergetics.content.converter.ConverterDevice;
import com.george_vi.electroenergetics.devices.device.DevicesSavedData;
import com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData;
import net.minecraft.server.level.ServerLevel;

/** No Java energy reservoir: integer FE moves only at an idle, accepted native boundary. */
public final class NativeFeBoundary {
    private NativeFeBoundary(){}
    public static int capacity(){return (int)Math.min(Integer.MAX_VALUE,Math.floor(ModConfigs.server().nativeConverterBufferJoules.getF()/ModConfigs.server().nativeJoulesPerFe.getF()));}
    public static boolean direction(ConverterBlockEntity be,boolean receive){
        if(!(be.getLevel() instanceof ServerLevel level))return false;
        var device=DevicesSavedData.load(level).getDevice(be.getBlockPos(),ConverterDevice.class);
        return device!=null && device.isSource==receive;
    }
    public static int transfer(ConverterBlockEntity be,int amount,boolean receive,boolean simulate){
        if(amount<=0 || !(be.getLevel() instanceof ServerLevel level))return 0;
        var device=DevicesSavedData.load(level).getDevice(be.getBlockPos(),ConverterDevice.class);
        if(device==null)return 0;
        var access=(CeeSimulationAccess)InfrastructureSavedData.load(level).ticker;
        var branch=CeeNativeWorld.branchId(device,"fe-buffer");
        double unit=ModConfigs.server().nativeJoulesPerFe.getF(),maximum=ModConfigs.server().nativeConverterBufferJoules.getF();
        int low=0,high=Math.min(amount,capacity());
        while(low<high){int middle=low+(int)(((long)high-low+1)/2);if(access.electromagnetism$exchangeEnergy(branch,(receive?1:-1)*middle*unit,maximum,false))low=middle;else high=middle-1;}
        if(low==0 || simulate)return low;
        return access.electromagnetism$exchangeEnergy(branch,(receive?1:-1)*low*unit,maximum,true)?low:0;
    }
    public static int transferLimited(ConverterBlockEntity be,int amount,boolean receive,boolean simulate){
        if(amount<=0||!direction(be,receive)||!(be instanceof NativeConverterBoundary boundary))return 0;
        var state=boundary.electromagnetism$converter();if(!state.problem().isEmpty())return 0;
        double unit=ModConfigs.server().nativeJoulesPerFe.getF();
        int moved=transfer(be,Math.min(amount,state.allowance(be,unit)),receive,simulate);
        if(!simulate)state.exchanged(moved,unit);return moved;
    }
}
