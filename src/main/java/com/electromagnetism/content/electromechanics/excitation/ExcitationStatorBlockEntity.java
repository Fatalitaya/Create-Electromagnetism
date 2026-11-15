package com.electromagnetism.content.electromechanics.excitation;

import com.simibubi.create.foundation.utility.CreateLang;

import com.electromagnetism.integration.cee.display.MeterReading;
import com.electromagnetism.simulation.SimulationProtocol.Reading;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import java.util.List;

/** Ephemeral accepted coil observations. Flux and current remain owned by Rust. */
public class ExcitationStatorBlockEntity extends SmartBlockEntity implements IHaveGoggleInformation {
    private double current=Double.NaN,rmsCurrent=Double.NaN,temperature=Double.NaN;
    public ExcitationStatorBlockEntity(BlockEntityType<?> type,BlockPos pos,BlockState state){super(type,pos,state);}
    @Override public void addBehaviours(List<BlockEntityBehaviour> behaviours){}
    public void acceptNative(Reading reading){current=reading.meanCurrent();rmsCurrent=reading.rmsCurrent();temperature=reading.temperatureKelvin().orElse(Double.NaN);sendData();}
    public void nativeUnavailable(){if(!Double.isFinite(current))return;current=rmsCurrent=temperature=Double.NaN;sendData();}
    @Override public boolean addToGoggleTooltip(List<Component> tooltip,boolean sneak){
        CreateLang.builder().add(Component.translatable("electromagnetism.gui.excitation.title")).forGoggles(tooltip);
        if(!Double.isFinite(current))CreateLang.builder().add(Component.translatable("electromagnetism.simulation.unavailable")).forGoggles(tooltip);
        else {
            CreateLang.builder().add(Component.translatable("electromagnetism.measurement.mean_current").append(": ").append(MeterReading.automatic(current,"A"))).forGoggles(tooltip);
            CreateLang.builder().add(Component.literal("RMS: ").append(MeterReading.automatic(rmsCurrent,"A"))).forGoggles(tooltip);
            CreateLang.builder().add(MeterReading.automatic(temperature,"K")).forGoggles(tooltip);
        }
        return true;
    }
    @Override protected void read(CompoundTag tag,HolderLookup.Provider registry,boolean packet){super.read(tag,registry,packet);current=rmsCurrent=temperature=Double.NaN;if(packet&&tag.contains("NativeCoilCurrent")){current=tag.getDouble("NativeCoilCurrent");rmsCurrent=tag.getDouble("NativeCoilRms");temperature=tag.getDouble("NativeCoilKelvin");}}
    @Override protected void write(CompoundTag tag,HolderLookup.Provider registry,boolean packet){super.write(tag,registry,packet);if(packet){tag.putDouble("NativeCoilCurrent",current);tag.putDouble("NativeCoilRms",rmsCurrent);tag.putDouble("NativeCoilKelvin",temperature);}}
}
