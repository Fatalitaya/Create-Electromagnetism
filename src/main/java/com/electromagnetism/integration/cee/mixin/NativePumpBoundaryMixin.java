package com.electromagnetism.integration.cee.mixin;

import com.simibubi.create.foundation.utility.CreateLang;
import com.electromagnetism.integration.cee.nativebridge.*;
import com.george_vi.electroenergetics.content.electric_pump.ElectricPumpBlockEntity;
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
    @Override public NativeFluidPump electromagnetism$pump(){return electromagnetism$pump;}
    @Inject(method="getSpeed",at=@At("HEAD"),cancellable=true)
    private void electromagnetism$pressure(CallbackInfoReturnable<Float> cir){cir.setReturnValue(0f);}
    @Inject(method="setVoltage",at=@At("HEAD"),cancellable=true)
    private void electromagnetism$legacyVoltage(float value,CallbackInfo ci){ci.cancel();}
    @Inject(method="write",at=@At("TAIL"))
    private void electromagnetism$write(CompoundTag tag,HolderLookup.Provider registry,boolean packet,CallbackInfo ci){if(!packet)electromagnetism$pump.save(tag,registry);}
    @Inject(method="read",at=@At("TAIL"))
    private void electromagnetism$read(CompoundTag tag,HolderLookup.Provider registry,boolean packet,CallbackInfo ci){if(!packet)electromagnetism$pump.read(tag,registry);}
    @Inject(method="addToGoggleTooltip",at=@At("HEAD"),cancellable=true)
    private void electromagnetism$tooltip(List<Component> tooltip,boolean sneak,CallbackInfoReturnable<Boolean> cir){CreateLang.builder().add(Component.translatable("electromagnetism.pump.native_boundary")).forGoggles(tooltip);if(!electromagnetism$pump.problem().isEmpty())CreateLang.builder().add(Component.literal(electromagnetism$pump.problem())).forGoggles(tooltip);cir.setReturnValue(true);}
}
