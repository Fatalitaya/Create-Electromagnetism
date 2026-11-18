package com.electromagnetism.integration.cee.mixin;

import com.electromagnetism.integration.cee.nativebridge.*;
import com.george_vi.electroenergetics.content.electric_pump.ElectricPumpBlockEntity;
import com.george_vi.electroenergetics.foundation.CEELang;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.util.List;
@Mixin(value=ElectricPumpBlockEntity.class,remap=false)
public abstract class NativePumpBoundaryMixin implements NativePumpBoundary {
    @Unique private final NativeFluidPump electromagnetism$pump=new NativeFluidPump();
    @Unique private double electromagnetism$watts=Double.NaN;
    @Override public NativeFluidPump electromagnetism$pump(){return electromagnetism$pump;}
    @Override public void electromagnetism$displayPower(java.util.OptionalDouble reading) {
        double watts=reading.orElse(Double.NaN);
        if(Double.compare(electromagnetism$watts,watts)==0)return;
        electromagnetism$watts=watts;
        ((ElectricPumpBlockEntity)(Object)this).sendData();
    }
    @Inject(method="getSpeed",at=@At("HEAD"),cancellable=true)
    private void electromagnetism$pressure(CallbackInfoReturnable<Float> cir){cir.setReturnValue(0f);}
    @Inject(method="setVoltage",at=@At("HEAD"),cancellable=true)
    private void electromagnetism$legacyVoltage(float value,CallbackInfo ci){ci.cancel();}
    @Inject(method="write",at=@At("TAIL"))
    private void electromagnetism$write(CompoundTag tag,HolderLookup.Provider registry,boolean packet,CallbackInfo ci){
        if(packet)tag.putDouble("NativeWatts",electromagnetism$watts);
        else electromagnetism$pump.save(tag,registry);
    }
    @Inject(method="read",at=@At("TAIL"))
    private void electromagnetism$read(CompoundTag tag,HolderLookup.Provider registry,boolean packet,CallbackInfo ci){
        if(packet)electromagnetism$watts=tag.getDouble("NativeWatts");
        else electromagnetism$pump.read(tag,registry);
    }
    @Inject(method="addToGoggleTooltip",at=@At("HEAD"),cancellable=true)
    private void electromagnetism$tooltip(List<Component> tooltip,boolean sneak,CallbackInfoReturnable<Boolean> cir){
        if(Double.isNaN(electromagnetism$watts)){cir.setReturnValue(false);return;}
        CEELang.translate("gui.goggles.energy_consumption").style(ChatFormatting.GRAY).forGoggles(tooltip);
        CEELang.formatPower(electromagnetism$watts).style(ChatFormatting.AQUA).forGoggles(tooltip,1);
        cir.setReturnValue(true);
    }
}
