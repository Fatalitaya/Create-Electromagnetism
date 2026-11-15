package com.electromagnetism.integration.cee.mixin;

import com.electromagnetism.integration.cee.nativebridge.NativeFuseHolderDisplay;
import com.george_vi.electroenergetics.content.fuse.FuseHolderBlockEntity;
import com.george_vi.electroenergetics.content.fuse.fuse_held.FuseHoldable;
import net.createmod.catnip.data.Pair;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.UUID;

@Mixin(value=FuseHolderBlockEntity.class,remap=false)
public abstract class NativeFuseHolderDisplayMixin implements NativeFuseHolderDisplay {
    @Shadow Pair<FuseHoldable,CompoundTag> firstFuse;
    @Shadow Pair<FuseHoldable,CompoundTag> secondFuse;
    @Inject(method="updateFuses",at=@At("HEAD"))
    private void electromagnetism$newAttachments(CallbackInfo ci) {
        electromagnetism$identify(firstFuse);electromagnetism$identify(secondFuse);
    }
    @Inject(method="updateFuses",at=@At("TAIL"))
    private void electromagnetism$commitCommands(CallbackInfo ci) {
        var be=(FuseHolderBlockEntity)(Object)this;
        if(be.getLevel() instanceof net.minecraft.server.level.ServerLevel level
                && com.george_vi.electroenergetics.devices.device.DevicesSavedData.load(level).getDevice(be.getBlockPos()) instanceof com.electromagnetism.integration.cee.nativebridge.NativeFuseHolder holder)
            holder.electromagnetism$replaceSlotCommands();
    }
    @Unique private static void electromagnetism$identify(Pair<FuseHoldable,CompoundTag> slot) {
        if(slot!=null && !slot.getSecond().contains("NativeInstance"))slot.getSecond().putUUID("NativeInstance",UUID.randomUUID());
    }
    @Override public void electromagnetism$slots(FuseHoldable first,CompoundTag one,FuseHoldable second,CompoundTag two) {
        firstFuse=first==null?null:Pair.of(first,one);secondFuse=second==null?null:Pair.of(second,two);
        var be=(FuseHolderBlockEntity)(Object)this;be.setChanged();be.sendData();
    }
    @Inject(method="read",at=@At("TAIL"))
    private void electromagnetism$clearReadings(CompoundTag tag,HolderLookup.Provider registries,boolean clientPacket,CallbackInfo ci) {
        for(var slot:java.util.Arrays.asList(firstFuse,secondFuse))if(slot!=null && (!clientPacket || !slot.getSecond().getBoolean("NativeAvailable"))) {
            slot.getSecond().putFloat("Light",0);slot.getSecond().putBoolean("NativeAvailable",false);
        }
    }
}
