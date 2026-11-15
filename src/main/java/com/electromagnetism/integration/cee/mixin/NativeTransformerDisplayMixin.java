package com.electromagnetism.integration.cee.mixin;

import com.electromagnetism.integration.cee.nativebridge.NativeTransformerDisplay;
import com.george_vi.electroenergetics.content.transmission_distribution.transformer.TransformerBlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.util.List;

@Mixin(value={TransformerBlockEntity.class,com.george_vi.electroenergetics.content.transmission_distribution.transformer.TransformerCoreBlockEntity.class},remap=false)
public abstract class NativeTransformerDisplayMixin implements NativeTransformerDisplay {
    @Shadow(remap=false) protected double power;
    @Shadow(remap=false) protected double primaryVoltage;
    @Shadow(remap=false) protected double secondaryVoltage;
    @Inject(method="<init>",at=@At("RETURN"))
    private void electromagnetism$initial(CallbackInfo ci){power=primaryVoltage=secondaryVoltage=Double.NaN;}
    @Override public void electromagnetism$acceptTransformer(double primary,double secondary,double watts) {
        boolean unavailable=!Double.isFinite(power);power=watts;primaryVoltage=primary;secondaryVoltage=secondary;
        if(unavailable)((com.simibubi.create.foundation.blockEntity.SmartBlockEntity)(Object)this).sendData();
    }
    @Override public void electromagnetism$transformerUnavailable() {
        boolean changed=Double.isFinite(power);power=primaryVoltage=secondaryVoltage=Double.NaN;
        if(changed)((com.simibubi.create.foundation.blockEntity.SmartBlockEntity)(Object)this).sendData();
    }
    @Inject(method="read",at=@At("TAIL"))
    private void electromagnetism$read(CompoundTag tag,HolderLookup.Provider registries,boolean clientPacket,CallbackInfo ci) {
        if(!clientPacket || !tag.getBoolean("NativeAvailable"))power=primaryVoltage=secondaryVoltage=Double.NaN;
    }
    @Inject(method="write",at=@At("TAIL"))
    private void electromagnetism$write(CompoundTag tag,HolderLookup.Provider registries,boolean clientPacket,CallbackInfo ci) {
        if(clientPacket)tag.putBoolean("NativeAvailable",Double.isFinite(power));
    }
    @Inject(method="addToGoggleTooltip",at=@At("HEAD"),cancellable=true)
    private void electromagnetism$tooltip(List<Component> tooltip,boolean sneaking,CallbackInfoReturnable<Boolean> cir) {
        if(!Double.isFinite(power)){tooltip.add(Component.translatable("electromagnetism.simulation.unavailable"));cir.setReturnValue(true);}
    }
}
