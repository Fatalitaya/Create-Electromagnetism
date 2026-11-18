package com.electromagnetism.integration.cee.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.electromagnetism.integration.cee.nativebridge.NativeCellDisplay;
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
        if(!Double.isFinite(electromagnetism$soc[0]) || (isDoubleCell && !Double.isFinite(electromagnetism$soc[1])))
            cir.setReturnValue(false);
    }
    @ModifyArg(method="addToGoggleTooltip", at=@At(value="INVOKE",
            target="Lcom/george_vi/electroenergetics/foundation/CEELang;formatEnergy(D)Lnet/createmod/catnip/lang/LangBuilder;"))
    private double electromagnetism$storedEnergy(double ignored, @Local(index=9) boolean firstCell) {
        return electromagnetism$energy[firstCell ? 0 : 1] / 3600;
    }
    @ModifyArg(method="addToGoggleTooltip", at=@At(value="INVOKE",
            target="Lcom/simibubi/create/foundation/utility/CreateLang;number(D)Lnet/createmod/catnip/lang/LangBuilder;"))
    private double electromagnetism$chargePercent(double ignored, @Local(index=9) boolean firstCell) {
        return 100*electromagnetism$soc[firstCell ? 0 : 1];
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
