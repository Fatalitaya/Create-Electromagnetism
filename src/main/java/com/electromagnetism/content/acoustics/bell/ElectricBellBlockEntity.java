package com.electromagnetism.content.acoustics.bell;

import com.electromagnetism.registry.ModBlockEntityTypes;
import com.electromagnetism.platform.ClientHooks;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/** Synchronize volume and pitch to clients. Client hooks manage the playback instance. */
public class ElectricBellBlockEntity extends SmartBlockEntity {
    private float volume;
    private boolean nativeAvailable;
    private float pitch;
    /** Retain the last playback parameters for the bell's final ring. */
    private float prevVolume, prevPitch;
    private boolean hasSoundInstance;

    public ElectricBellBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
    }

    @Override
    public void tick() {
        if (level != null && level.isClientSide)
            ClientHooks.bellAudio.accept(this);
    }

    public float getVolume() {
        return volume;
    }

    public float getPitch() {
        return pitch;
    }

    public float getPrevVolume() {
        return prevVolume;
    }

    public float getPrevPitch() {
        return prevPitch;
    }

    public boolean hasSoundInstance() {
        return hasSoundInstance;
    }

    public void markSoundStarted() {
        hasSoundInstance = true;
    }

    public void markSoundStopped() {
        hasSoundInstance = false;
    }

    /** Notify tracking clients only when playback parameters change. */
    public boolean nativeAvailable(){return nativeAvailable;}
    /** Derive the sound envelope from accepted coil power. */
    public void acceptNative(com.electromagnetism.simulation.SimulationProtocol.Reading reading) {
        boolean changed=!nativeAvailable;nativeAvailable=true;
        double strength=Math.sqrt(Math.max(0,reading.activePower())/20.0);
        setAudio((float)Math.min(1,strength),(float)(0.75+0.5*Math.min(1,strength)));
        if(changed)sendData();
    }
    public void nativeUnavailable(){boolean changed=nativeAvailable;nativeAvailable=false;setAudio(0,1);if(changed)sendData();}
    public void setAudio(float volume, float pitch) {
        boolean changed = Math.abs(volume - this.volume) > 0.005f || Math.abs(pitch - this.pitch) > 0.005f;
        this.volume = volume;
        this.pitch = pitch;
        if (changed)
            sendData();
    }

    @Override
    public void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (clientPacket) {
            tag.putBoolean("NativeAvailable",nativeAvailable);
            tag.putFloat("Volume", volume);
            tag.putFloat("Pitch", pitch);
        }
    }

    @Override
    public void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (clientPacket) {
            prevVolume=volume;prevPitch=pitch;
            nativeAvailable=tag.getBoolean("NativeAvailable");
            volume=nativeAvailable?tag.getFloat("Volume"):0;
            pitch=nativeAvailable?tag.getFloat("Pitch"):1;
        }
    }
}
