package com.electromagnetism.integration.cee.mixin;

import com.simibubi.create.foundation.utility.CreateLang;

import com.electromagnetism.integration.cee.nativebridge.NativeEquipmentDisplay;
import com.electromagnetism.integration.cee.display.MeterReading;
import com.george_vi.electroenergetics.content.converter.ConverterBlockEntity;
import com.george_vi.electroenergetics.content.rotor.AlternatorBrushesBlockEntity;
import com.george_vi.electroenergetics.content.transmission_distribution.voltage_regulator.VoltageRegulatorBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.util.*;

@Mixin(value={ConverterBlockEntity.class,AlternatorBrushesBlockEntity.class,VoltageRegulatorBlockEntity.class},remap=false)
public abstract class NativeEquipmentDisplayMixin implements NativeEquipmentDisplay {
    @Unique private List<Value> electromagnetism$values=List.of();
    @Unique private double electromagnetism$start,electromagnetism$end;
    @Override public void electromagnetism$display(List<Value> values,double start,double end){
        if(values.size()>8 || !Double.isFinite(start)||!Double.isFinite(end)||start<0||end<start)
            throw new IllegalArgumentException("Invalid native display interval");
        var next=List.copyOf(values);
        if(next.stream().anyMatch(v->!Double.isFinite(v.value())))next=List.of();
        if(next.equals(electromagnetism$values)&&start==electromagnetism$start&&end==electromagnetism$end)return;
        electromagnetism$values=next;electromagnetism$start=start;electromagnetism$end=end;
        ((SmartBlockEntity)(Object)this).sendData();
    }
    @Inject(method="addToGoggleTooltip",at=@At("HEAD"),cancellable=true)
    private void electromagnetism$tooltip(List<Component> tooltip,boolean sneak,CallbackInfoReturnable<Boolean> ci){
        if(electromagnetism$values.isEmpty())CreateLang.builder().add(Component.translatable("electromagnetism.simulation.unavailable")).forGoggles(tooltip);
        else {
            for(var value:electromagnetism$values)CreateLang.builder().add(Component.translatable(value.label()).append(": ").append(MeterReading.automatic(value.value(),value.unit()))).forGoggles(tooltip);
            CreateLang.builder().add(Component.translatable("electromagnetism.measurement.accepted_window",MeterReading.automatic(electromagnetism$start,"s"),MeterReading.automatic(electromagnetism$end,"s"))).forGoggles(tooltip);
        }
        ci.setReturnValue(true);
    }
    @Inject(method="write",at=@At("TAIL"))
    private void electromagnetism$write(CompoundTag tag,HolderLookup.Provider registry,boolean packet,CallbackInfo ci){
        if(!packet)return;
        var list=new ListTag();for(var value:electromagnetism$values){var item=new CompoundTag();item.putString("Label",value.label());item.putDouble("Value",value.value());item.putString("Unit",value.unit());list.add(item);}
        tag.put("NativeReadout",list);tag.putDouble("NativeReadoutStart",electromagnetism$start);tag.putDouble("NativeReadoutEnd",electromagnetism$end);
    }
    @Inject(method="read",at=@At("TAIL"))
    private void electromagnetism$read(CompoundTag tag,HolderLookup.Provider registry,boolean packet,CallbackInfo ci){
        electromagnetism$values=List.of();
        if(!packet)return;
        var list=tag.getList("NativeReadout",Tag.TAG_COMPOUND);if(list.size()>8)return;
        var values=new ArrayList<Value>();for(var raw:list){var item=(CompoundTag)raw;double value=item.getDouble("Value");if(!Double.isFinite(value))return;values.add(new Value(item.getString("Label"),value,item.getString("Unit")));}
        electromagnetism$values=List.copyOf(values);electromagnetism$start=tag.getDouble("NativeReadoutStart");electromagnetism$end=tag.getDouble("NativeReadoutEnd");
    }
}
