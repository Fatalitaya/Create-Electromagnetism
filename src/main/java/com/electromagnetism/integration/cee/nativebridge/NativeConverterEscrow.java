package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.config.ModConfigs;
import com.george_vi.electroenergetics.content.converter.*;
import com.george_vi.electroenergetics.devices.device.DevicesSavedData;
import com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.Capabilities;

/** Durable pending external FE parcels, not a Java electrical simulation buffer. */
public final class NativeConverterEscrow {
    private int incoming,outgoing;
    private double incomingUnit;
    private long tick=Long.MIN_VALUE;
    private long budgetTick=Long.MIN_VALUE;
    private double exchangedJoules;
    private boolean busy;
    private String problem="";
    public String problem(){return problem;}
    public int allowance(ConverterBlockEntity be,double unit){
        long now=be.getLevel().getGameTime();if(budgetTick!=now){budgetTick=now;exchangedJoules=0;}
        return (int)Math.min(Integer.MAX_VALUE,Math.floor(Math.max(0,ModConfigs.server().nativeConverterMaxWatts.getF()*0.05-exchangedJoules)/unit));
    }
    public void exchanged(int amount,double unit){exchangedJoules+=amount*unit;}
    private void importPending(ConverterBlockEntity be,CeeSimulationAccess access,java.util.UUID buffer,double maximum){
        int amount=Math.min(incoming,allowance(be,incomingUnit));
        if(amount>0&&access.electromagnetism$exchangeEnergy(buffer,amount*incomingUnit,maximum,true)){
            incoming-=amount;exchanged(amount,incomingUnit);be.setChanged();
        }
    }
    public void tick(ConverterBlockEntity be){
        if(busy||!(be.getLevel() instanceof ServerLevel level)||tick==level.getGameTime()||!problem.isEmpty())return;
        tick=level.getGameTime();
        var device=DevicesSavedData.load(level).getDevice(be.getBlockPos(),ConverterDevice.class);if(device==null)return;
        var facing=be.getBlockState().getValue(ConverterBlock.FACING);var adjacent=be.getBlockPos().relative(facing.getOpposite());
        if(!level.isLoaded(adjacent))return;
        var external=level.getCapability(Capabilities.EnergyStorage.BLOCK,adjacent,facing);if(external==null)return;
        var access=(CeeSimulationAccess)InfrastructureSavedData.load(level).ticker;var buffer=CeeNativeWorld.branchId(device,"fe-buffer");
        double maximum=ModConfigs.server().nativeConverterBufferJoules.getF(),unit=ModConfigs.server().nativeJoulesPerFe.getF();
        int limit=(int)Math.min(Integer.MAX_VALUE,Math.floor(ModConfigs.server().nativeConverterMaxWatts.getF()*0.05/unit));
        busy=true;
        try {
            if(incoming>0) {
                importPending(be,access,buffer,maximum);if(incoming>0)return;
            }
            if(outgoing>0) {
                int accepted=external.receiveEnergy(outgoing,false);
                if(accepted<0||accepted>outgoing){problem="External FE destination violated its transfer contract";be.setChanged();return;}
                outgoing-=accepted;be.setChanged();if(outgoing>0)return;
            }
            if(device.isSource) {
                int possible=NativeFeBoundary.transferLimited(be,limit,true,true);if(possible<=0)return;
                int extracted=external.extractEnergy(possible,false);
                if(extracted<0||extracted>possible){problem="External FE source violated its transfer contract";be.setChanged();return;}
                incoming=extracted;incomingUnit=unit;be.setChanged();
                if(incoming>0)importPending(be,access,buffer,maximum);
            } else {
                int possible=NativeFeBoundary.transferLimited(be,limit,false,true);if(possible<=0)return;
                int capacity=external.receiveEnergy(possible,true);if(capacity<0||capacity>possible){problem="Invalid external FE simulation result";be.setChanged();return;}
                if(capacity==0)return;
                int debited=NativeFeBoundary.transferLimited(be,capacity,false,false);if(debited==0)return;
                outgoing=debited;be.setChanged();
                int accepted=external.receiveEnergy(outgoing,false);
                if(accepted<0||accepted>outgoing){problem="External FE destination violated its transfer contract";be.setChanged();return;}
                outgoing-=accepted;be.setChanged();
            }
        }catch(RuntimeException failed){problem="External FE transaction failed: "+failed.getClass().getSimpleName();be.setChanged();}
        finally{busy=false;}
    }
    public void save(CompoundTag tag){var state=new CompoundTag();state.putInt("Incoming",incoming);state.putInt("Outgoing",outgoing);state.putDouble("IncomingJoulesPerFe",incomingUnit);state.putString("Problem",problem);tag.put("NativeFeEscrow",state);}
    public void read(CompoundTag tag){var state=tag.getCompound("NativeFeEscrow");incoming=state.getInt("Incoming");outgoing=state.getInt("Outgoing");incomingUnit=state.getDouble("IncomingJoulesPerFe");problem=state.getString("Problem");if(incoming<0||outgoing<0||(incoming>0&&(!Double.isFinite(incomingUnit)||incomingUnit<=0)))problem="Invalid persisted external FE parcel";}
}
