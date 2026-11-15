package com.electromagnetism.integration.cee.mixin;
import com.electromagnetism.integration.cee.nativebridge.NativeBulbDisplay;
import com.george_vi.electroenergetics.content.bulb.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.HolderLookup;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=BulbBlockEntity.class,remap=false)
public abstract class NativeBulbDisplayMixin implements NativeBulbDisplay {
    @Shadow float light;
    @Shadow net.createmod.catnip.animation.LerpedFloat smoothLight;
    @Unique private boolean electromagnetism$available;
    @Override public void electromagnetism$setNativeLight(double fraction,boolean available) {
        var self=(BulbBlockEntity)(Object)this;float next=available?(float)Math.clamp(fraction,0,1):0;
        boolean changed=electromagnetism$available!=available || Math.abs(light-next)>0.01;
        electromagnetism$available=available;
        var level=self.getLevel();var state=self.getBlockState();
        if(level!=null && !level.isClientSide && state.getBlock() instanceof BulbBlock && state.getValue(BulbBlock.LIGHT)!=(int)Math.round(next*15))
            level.setBlockAndUpdate(self.getBlockPos(),state.setValue(BulbBlock.LIGHT,(int)Math.round(next*15)));
        if(changed)self.setLight(next);
    }
    @Inject(method="read",at=@At("TAIL"))
    private void electromagnetism$read(CompoundTag tag,HolderLookup.Provider registries,boolean clientPacket,CallbackInfo ci) {
        electromagnetism$available=clientPacket && tag.getBoolean("NativeAvailable");
        if(!electromagnetism$available){light=0;smoothLight=net.createmod.catnip.animation.LerpedFloat.linear();}
    }
    @Inject(method="write",at=@At("TAIL"))
    private void electromagnetism$write(CompoundTag tag,HolderLookup.Provider registries,boolean clientPacket,CallbackInfo ci){if(clientPacket)tag.putBoolean("NativeAvailable",electromagnetism$available);}
}
