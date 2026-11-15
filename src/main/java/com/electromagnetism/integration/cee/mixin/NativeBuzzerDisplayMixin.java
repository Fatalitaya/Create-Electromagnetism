package com.electromagnetism.integration.cee.mixin;
import com.electromagnetism.integration.cee.nativebridge.NativeBuzzerDisplay;
import com.george_vi.electroenergetics.content.buzzer.BuzzerBlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.HolderLookup;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=BuzzerBlockEntity.class,remap=false)
public abstract class NativeBuzzerDisplayMixin implements NativeBuzzerDisplay {
    @Shadow double voltage;
    @Unique private boolean electromagnetism$available;
    @Override public void electromagnetism$buzzer(double rmsVoltage,boolean available) {
        double next=available?rmsVoltage:0;boolean changed=electromagnetism$available!=available || Math.abs(voltage-next)>0.1;
        electromagnetism$available=available;voltage=next;if(changed)((BuzzerBlockEntity)(Object)this).sendData();
    }
    @Inject(method="read",at=@At("TAIL"))
    private void electromagnetism$read(CompoundTag tag,HolderLookup.Provider registries,boolean clientPacket,CallbackInfo ci) {
        electromagnetism$available=clientPacket && tag.getBoolean("NativeAvailable");if(!electromagnetism$available)voltage=0;
    }
    @Inject(method="write",at=@At("TAIL"))
    private void electromagnetism$write(CompoundTag tag,HolderLookup.Provider registries,boolean clientPacket,CallbackInfo ci){if(clientPacket)tag.putBoolean("NativeAvailable",electromagnetism$available);}
}
