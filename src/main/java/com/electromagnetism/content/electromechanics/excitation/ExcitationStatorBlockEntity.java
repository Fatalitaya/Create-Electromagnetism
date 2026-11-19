package com.electromagnetism.content.electromechanics.excitation;

import com.electromagnetism.simulation.SimulationProtocol.Reading;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import java.util.List;

/** Ephemeral accepted coil observations. Flux and current remain owned by Rust. */
public class ExcitationStatorBlockEntity extends SmartBlockEntity {
    private double current=Double.NaN,rmsCurrent=Double.NaN,temperature=Double.NaN;
    public ExcitationStatorBlockEntity(BlockEntityType<?> type,BlockPos pos,BlockState state){super(type,pos,state);}
    @Override public void addBehaviours(List<BlockEntityBehaviour> behaviours){}
    public void acceptNative(Reading reading){current=reading.meanCurrent();rmsCurrent=reading.rmsCurrent();temperature=reading.temperatureKelvin().orElse(Double.NaN);sendData();}
    public void nativeUnavailable(){if(!Double.isFinite(current))return;current=rmsCurrent=temperature=Double.NaN;sendData();}
    @Override protected void read(CompoundTag tag,HolderLookup.Provider registry,boolean packet){super.read(tag,registry,packet);current=rmsCurrent=temperature=Double.NaN;if(packet&&tag.contains("NativeCoilCurrent")){current=tag.getDouble("NativeCoilCurrent");rmsCurrent=tag.getDouble("NativeCoilRms");temperature=tag.getDouble("NativeCoilKelvin");}}
    @Override protected void write(CompoundTag tag,HolderLookup.Provider registry,boolean packet){super.write(tag,registry,packet);if(packet){tag.putDouble("NativeCoilCurrent",current);tag.putDouble("NativeCoilRms",rmsCurrent);tag.putDouble("NativeCoilKelvin",temperature);}}
}
