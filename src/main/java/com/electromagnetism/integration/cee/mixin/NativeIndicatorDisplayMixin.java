package com.electromagnetism.integration.cee.mixin;
import com.electromagnetism.integration.cee.nativebridge.NativeIndicatorDisplay;
import com.george_vi.electroenergetics.content.indicator_bulb.IndicatorBulbBlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.HolderLookup;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=IndicatorBulbBlockEntity.class,remap=false)
public abstract class NativeIndicatorDisplayMixin implements NativeIndicatorDisplay {
    @Shadow float firstLight;
    @Shadow float secondLight;
    @Shadow net.createmod.catnip.animation.LerpedFloat firstSmoothLight;
    @Shadow net.createmod.catnip.animation.LerpedFloat secondSmoothLight;
    @Unique private boolean electromagnetism$available;
    @Override public void electromagnetism$indicators(double first,double second,boolean available) {
        float a=available?(float)Math.clamp(first,0,1):0,b=available?(float)Math.clamp(second,0,1):0;
        boolean changed=electromagnetism$available!=available || Math.abs(a-firstLight)>0.01 || Math.abs(b-secondLight)>0.01;
        electromagnetism$available=available;firstLight=a;secondLight=b;
        if(changed)((IndicatorBulbBlockEntity)(Object)this).sendData();
    }
    @Inject(method="read",at=@At("TAIL"))
    private void electromagnetism$read(CompoundTag tag,HolderLookup.Provider registries,boolean clientPacket,CallbackInfo ci) {
        electromagnetism$available=clientPacket && tag.getBoolean("NativeAvailable");
        if(!electromagnetism$available){firstLight=secondLight=0;firstSmoothLight=net.createmod.catnip.animation.LerpedFloat.linear();secondSmoothLight=net.createmod.catnip.animation.LerpedFloat.linear();}
    }
    @Inject(method="write",at=@At("TAIL"))
    private void electromagnetism$write(CompoundTag tag,HolderLookup.Provider registries,boolean clientPacket,CallbackInfo ci){if(clientPacket)tag.putBoolean("NativeAvailable",electromagnetism$available);}
}
