package com.electromagnetism.integration.cee.mixin;

import com.electromagnetism.integration.cee.nativebridge.NativeCellDisplay;
import com.electromagnetism.integration.cee.display.MeterReading;
import com.george_vi.electroenergetics.content.accumulator.AccumulatorBlockEntity;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.util.List;

@Mixin(value=AccumulatorBlockEntity.class,remap=false)
public abstract class NativeCellDisplayMixin implements NativeCellDisplay {
    @Shadow public boolean isDoubleCell;
    @Unique private final double[] electromagnetism$soc={Double.NaN,Double.NaN};
    @Unique private final double[] electromagnetism$energy={Double.NaN,Double.NaN};
    @Override public void electromagnetism$cell(int index,double soc,double energy){electromagnetism$soc[index]=soc;electromagnetism$energy[index]=energy;((AccumulatorBlockEntity)(Object)this).sendData();}
    @Override public void electromagnetism$cellsUnavailable(){java.util.Arrays.fill(electromagnetism$soc,Double.NaN);java.util.Arrays.fill(electromagnetism$energy,Double.NaN);((AccumulatorBlockEntity)(Object)this).sendData();}
    @Inject(method="addToGoggleTooltip",at=@At("HEAD"),cancellable=true)
    private void electromagnetism$tooltip(List<Component> tooltip,boolean sneak,CallbackInfoReturnable<Boolean> cir){
        for(int i=0;i<(isDoubleCell?2:1);i++) {
            tooltip.add(Component.translatable("electromagnetism.measurement.cell",i+1).append(": ").append(MeterReading.automatic(100*electromagnetism$soc[i],"%")));
            tooltip.add(Component.translatable("electromagnetism.measurement.mean_stored_energy").append(": ").append(MeterReading.automatic(electromagnetism$energy[i],"J")));
        }
        cir.setReturnValue(true);
    }
    @Inject(method="write",at=@At("TAIL"))
    private void electromagnetism$write(CompoundTag tag,HolderLookup.Provider registry,boolean packet,CallbackInfo ci){
        if(packet)for(int i=0;i<2;i++){tag.putDouble("NativeSoc"+i,electromagnetism$soc[i]);tag.putDouble("NativeMeanEnergy"+i,electromagnetism$energy[i]);}
    }
    @Inject(method="read",at=@At("TAIL"))
    private void electromagnetism$read(CompoundTag tag,HolderLookup.Provider registry,boolean packet,CallbackInfo ci){
        if(packet)for(int i=0;i<2;i++){electromagnetism$soc[i]=tag.contains("NativeSoc"+i)?tag.getDouble("NativeSoc"+i):Double.NaN;electromagnetism$energy[i]=tag.contains("NativeMeanEnergy"+i)?tag.getDouble("NativeMeanEnergy"+i):Double.NaN;}
    }
}
