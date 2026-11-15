package com.electromagnetism.integration.cee.mixin;

import com.electromagnetism.integration.cee.nativebridge.NativeFrequencyDisplay;
import com.george_vi.electroenergetics.content.frequency_meter.FrequencyMeterBlockEntity;
import net.createmod.catnip.animation.LerpedFloat;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.OptionalDouble;

@Mixin(value=FrequencyMeterBlockEntity.class,remap=false)
public abstract class NativeFrequencyDisplayMixin implements NativeFrequencyDisplay {
    @Shadow float frequency;
    @Shadow float prevFrequency;
    @Shadow LerpedFloat smoothFrequency;
    @Inject(method="<init>",at=@At("RETURN"))
    private void electromagnetism$initial(CallbackInfo ci){frequency=Float.NaN;}
    @Override public void electromagnetism$frequency(OptionalDouble hertz) {
        float next=hertz.isPresent()?(float)hertz.getAsDouble():Float.NaN;
        boolean changed=Float.isFinite(next)!=Float.isFinite(frequency) || Math.abs(next-frequency)>0.001;
        frequency=next;
        if(!Float.isFinite(next)){prevFrequency=0;smoothFrequency=LerpedFloat.angular().startWithValue(0);}
        if(changed)((FrequencyMeterBlockEntity)(Object)this).sendData();
    }
    @Inject(method="read",at=@At("TAIL"))
    private void electromagnetism$read(CompoundTag tag,HolderLookup.Provider registries,boolean clientPacket,CallbackInfo ci) {
        if(!clientPacket || !tag.getBoolean("NativeAvailable"))frequency=Float.NaN;
        prevFrequency=Float.isFinite(frequency)?frequency:0;
        smoothFrequency=LerpedFloat.angular().startWithValue(prevFrequency);
    }
    @Inject(method="write",at=@At("TAIL"))
    private void electromagnetism$write(CompoundTag tag,HolderLookup.Provider registries,boolean clientPacket,CallbackInfo ci) {
        if(clientPacket)tag.putBoolean("NativeAvailable",Float.isFinite(frequency));
    }
}
