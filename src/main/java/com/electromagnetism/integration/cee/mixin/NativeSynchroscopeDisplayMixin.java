package com.electromagnetism.integration.cee.mixin;

import com.simibubi.create.foundation.utility.CreateLang;

import com.electromagnetism.integration.cee.nativebridge.NativeSynchroscopeDisplay;
import com.george_vi.electroenergetics.content.synchroscope.SynchroscopeBlockEntity;
import net.createmod.catnip.animation.LerpedFloat;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.util.List;

@Mixin(value=SynchroscopeBlockEntity.class,remap=false)
public abstract class NativeSynchroscopeDisplayMixin implements NativeSynchroscopeDisplay {
    @Shadow float phaseOffset;
    @Shadow float prevPhaseOffset;
    @Shadow LerpedFloat smoothPhase;
    @Shadow public boolean validConnection;
    @Inject(method="<init>",at=@At("RETURN"))
    private void electromagnetism$initial(CallbackInfo ci){phaseOffset=Float.NaN;validConnection=false;}
    @Override public void electromagnetism$synchroscope(double degrees,boolean correctOrder) {
        float next=(float)degrees;
        boolean changed=Float.isFinite(next)!=Float.isFinite(phaseOffset) || Math.abs(next-phaseOffset)>0.1 || validConnection!=correctOrder;
        phaseOffset=next;validConnection=Float.isFinite(next) && correctOrder;
        if(!Float.isFinite(next)){prevPhaseOffset=0;smoothPhase=LerpedFloat.angular().startWithValue(0);}
        if(changed)((SynchroscopeBlockEntity)(Object)this).sendData();
    }
    @Inject(method="read",at=@At("TAIL"))
    private void electromagnetism$read(CompoundTag tag,HolderLookup.Provider registries,boolean clientPacket,CallbackInfo ci) {
        if(!clientPacket || !tag.getBoolean("NativeAvailable")){phaseOffset=Float.NaN;validConnection=false;}
        prevPhaseOffset=Float.isFinite(phaseOffset)?phaseOffset:0;
        smoothPhase=LerpedFloat.angular().startWithValue(prevPhaseOffset);
    }
    @Inject(method="write",at=@At("TAIL"))
    private void electromagnetism$write(CompoundTag tag,HolderLookup.Provider registries,boolean clientPacket,CallbackInfo ci) {
        if(clientPacket)tag.putBoolean("NativeAvailable",Float.isFinite(phaseOffset));
    }
    @Inject(method="addToTooltip",at=@At("HEAD"),cancellable=true)
    private void electromagnetism$unavailable(List<Component> tooltip,boolean sneaking,CallbackInfoReturnable<Boolean> ci) {
        if(!Float.isFinite(phaseOffset)){CreateLang.builder().add(Component.translatable("electromagnetism.simulation.unavailable")).forGoggles(tooltip);ci.setReturnValue(true);}
    }
}
