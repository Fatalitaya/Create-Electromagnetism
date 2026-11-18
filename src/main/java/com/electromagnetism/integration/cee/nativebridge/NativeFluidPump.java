package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.config.ModConfigs;
import com.electromagnetism.simulation.SimulationProtocol.Reading;
import com.george_vi.electroenergetics.content.electric_pump.ElectricPumpDevice;
import com.george_vi.electroenergetics.devices.device.DevicesSavedData;
import com.george_vi.electroenergetics.devices.device.SimulatedDevice;
import com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;

/** A durable fluid escrow. A drained parcel is never replayed, and is delivered
 * only after the exact native energy debit has committed. No Java energy buffer. */
public final class NativeFluidPump {
    enum BranchRole {
        MAIN("main"), RECTIFIER_1("pump/rectifier/1"), RECTIFIER_2("pump/rectifier/2"),
        RECTIFIER_3("pump/rectifier/3"), LIMIT("pump/limit"), BUFFER("pump-buffer");

        private final String role;
        BranchRole(String role){this.role=role;}
        UUID id(SimulatedDevice device){
            return CeeNativeWorld.branchId(device,role);
        }
    }

    static OptionalDouble inputPower(SimulatedDevice device,Map<UUID,Reading> readings) {
        double watts=0;
        for(var role:BranchRole.values()) {
            var reading=readings.get(role.id(device));
            if(reading==null)return OptionalDouble.empty();
            watts+=reading.activePower();
        }
        return OptionalDouble.of(Math.max(0,watts));
    }

    private FluidStack pending=FluidStack.EMPTY;
    private boolean paid,busy;
    private double parcelJoulesPerMillibucket;
    private long lastTick=Long.MIN_VALUE;
    private String problem="";
    public void tick(BlockEntity be){
        if(busy||!(be.getLevel() instanceof ServerLevel level)||lastTick==level.getGameTime())return;
        lastTick=level.getGameTime();
        if(!problem.isEmpty())return;
        var device=DevicesSavedData.load(level).getDevice(be.getBlockPos(),ElectricPumpDevice.class);if(device==null)return;
        var state=be.getBlockState();var facing=state.getValue(com.simibubi.create.content.fluids.pump.PumpBlock.FACING);
        var from=be.getBlockPos().relative(facing.getOpposite());var to=be.getBlockPos().relative(facing);
        if(!level.isLoaded(from)||!level.isLoaded(to))return;
        var source=level.getCapability(Capabilities.FluidHandler.BLOCK,from,facing);
        var target=level.getCapability(Capabilities.FluidHandler.BLOCK,to,facing.getOpposite());
        if(target==null)return;
        var access=(CeeSimulationAccess)InfrastructureSavedData.load(level).ticker;
        var buffer=BranchRole.BUFFER.id(device);
        double perMb=ModConfigs.server().nativePumpPressurePascals.getF()*1e-6/ModConfigs.server().nativePumpEfficiency.getF();
        busy=true;
        try {
            if(pending.isEmpty()) {
                if(source==null||source==target)return;
                var candidate=source.drain(ModConfigs.server().nativePumpMillibucketsPerTick.get(),IFluidHandler.FluidAction.SIMULATE);
                if(candidate.isEmpty())return;
                int volume=Math.min(candidate.getAmount(),target.fill(candidate,IFluidHandler.FluidAction.SIMULATE));if(volume<=0)return;
                if(!access.electromagnetism$exchangeEnergy(buffer,-volume*perMb,10000,false))return;
                candidate.setAmount(volume);
                pending=source.drain(candidate,IFluidHandler.FluidAction.EXECUTE);
                if(pending.isEmpty())return;
                if(!FluidStack.isSameFluidSameComponents(pending,candidate)||pending.getAmount()>volume){problem="Fluid source violated its transfer contract";be.setChanged();return;}
                paid=false;parcelJoulesPerMillibucket=perMb;be.setChanged();
            }
            if(!paid) {
                if(!access.electromagnetism$exchangeEnergy(buffer,-pending.getAmount()*parcelJoulesPerMillibucket,10000,true))return;
                paid=true;be.setChanged();
            }
            int filled=target.fill(pending.copy(),IFluidHandler.FluidAction.EXECUTE);
            if(filled<0||filled>pending.getAmount()){problem="Fluid destination violated its transfer contract";be.setChanged();return;}
            pending.shrink(filled);if(pending.isEmpty())paid=false;be.setChanged();
        } catch(RuntimeException failed){problem="External fluid transaction failed: "+failed.getClass().getSimpleName();be.setChanged();}
        finally {busy=false;}
    }
    public void save(CompoundTag tag,HolderLookup.Provider registry){
        var state=new CompoundTag();if(!pending.isEmpty())state.put("Parcel",pending.save(registry));state.putDouble("JoulesPerMillibucket",parcelJoulesPerMillibucket);state.putBoolean("Paid",paid);state.putString("Problem",problem);tag.put("NativeFluidEscrow",state);
    }
    public void read(CompoundTag tag,HolderLookup.Provider registry){
        var state=tag.getCompound("NativeFluidEscrow");pending=state.contains("Parcel")?FluidStack.parseOptional(registry,state.getCompound("Parcel")):FluidStack.EMPTY;
        paid=state.getBoolean("Paid");parcelJoulesPerMillibucket=state.getDouble("JoulesPerMillibucket");problem=state.getString("Problem");if(pending.getAmount()>1000)problem="Fluid escrow capacity exceeded";
        if(!pending.isEmpty()&&(!Double.isFinite(parcelJoulesPerMillibucket)||parcelJoulesPerMillibucket<=0))problem="Invalid fluid parcel energy contract";
    }
    public String problem(){return problem;}
}
