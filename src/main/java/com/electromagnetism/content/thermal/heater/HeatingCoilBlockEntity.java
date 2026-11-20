package com.electromagnetism.content.thermal.heater;

import com.electromagnetism.integration.create.processing.HeaterBlastingType;
import com.electromagnetism.integration.create.processing.HeaterSmokingType;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Select the fan processing mode from the latest synchronized coil temperature and notify
 * neighbours when it changes.
 */
public class HeatingCoilBlockEntity extends SmartBlockEntity {
    public enum State {
        COLD,
        SMOKING,
        BLASTING
    }

    /** Coil temperature for world interactions, in °C. */
    private float temperature = HeatingCoilDevice.AMBIENT_TEMPERATURE;
    private State state = State.COLD;
    private boolean burned;
    private boolean nativeAvailable;
    public void nativeUnavailable(){
        if(!nativeAvailable)return;nativeAvailable=false;state=State.COLD;
        if(level!=null){level.blockUpdated(worldPosition,getBlockState().getBlock());sendData();}
    }


    public void setBurned(boolean burned) {
        if (this.burned == burned)
            return;
        this.burned = burned;
        sendData();
    }

    public HeatingCoilBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
    }

    public void setTemperature(float temperature) {
        boolean changed = !nativeAvailable || Math.abs(this.temperature - temperature) > 0.5f;
        nativeAvailable=true;
        this.temperature = temperature;
        State next = stateOf(temperature);
        if (this.state != next) {
            this.state = next;
            if (level != null)
                level.blockUpdated(worldPosition, getBlockState().getBlock());
            sendData();
        } else if (changed)
            sendData();
    }

    public State getState() {
        return nativeAvailable && !burned ? state : State.COLD;
    }

    public float getTemperature() {
        return nativeAvailable ? temperature : Float.NaN;
    }

    /** Below 200 °C the coil is cold. Smoking begins at 200 °C and blasting at 400 °C. */
    private static State stateOf(float temperature) {
        if (temperature < 200f)
            return State.COLD;
        if (temperature < 400f)
            return State.SMOKING;
        return State.BLASTING;
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        nativeAvailable=clientPacket && tag.getBoolean("NativeAvailable");
        temperature = tag.getFloat("Temperature");
        burned = tag.getBoolean("Burned");
        state = stateOf(temperature);
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if(clientPacket)tag.putBoolean("NativeAvailable",nativeAvailable);
        tag.putFloat("Temperature", temperature);
        tag.putBoolean("Burned", burned);
    }
}
