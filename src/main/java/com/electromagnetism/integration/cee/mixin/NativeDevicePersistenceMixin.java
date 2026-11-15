package com.electromagnetism.integration.cee.mixin;

import com.electromagnetism.integration.cee.nativebridge.CeeDeviceIdentity;
import com.george_vi.electroenergetics.devices.device.DevicesSavedData;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Envelope identity avoids virtual read/write overrides and initialization parameter merges.
 * Save/load descriptors checked against the resolved CEE jar; no synthetic lambda injection.
 */
@Mixin(value = DevicesSavedData.class, remap = false)
public abstract class NativeDevicePersistenceMixin {
    @org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Final public ServerLevel level;
    @Inject(method="save",at=@At("HEAD"))
    private void electromagnetism$freezePhysics(CompoundTag tag,HolderLookup.Provider provider,CallbackInfoReturnable<CompoundTag> cir) {
        ((com.electromagnetism.integration.cee.nativebridge.NativeSableTransfers)this).electromagnetism$completeTransfers();
        ((com.electromagnetism.integration.cee.nativebridge.CeeSimulationAccess)
                com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData.load(level).ticker).electromagnetism$saveNative();
    }
    @Inject(method = "save", at = @At("RETURN"))
    private void electromagnetism$saveIdentities(CompoundTag tag, HolderLookup.Provider provider,
                                               CallbackInfoReturnable<CompoundTag> cir) {
        var devices = (DevicesSavedData)(Object)this;
        for (Tag entry : cir.getReturnValue().getList("Devices", Tag.TAG_COMPOUND)) {
            var envelope = (CompoundTag)entry;
            var device = devices.getDevice(DevicesSavedData.readBlockPos(envelope, "Pos"));
            if (device instanceof CeeDeviceIdentity identity)
                envelope.put("ElectromagnetismIdentity", identity.electromagnetism$saveIdentity());
        }
    }

    @Inject(method = "load(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)Lcom/george_vi/electroenergetics/devices/device/DevicesSavedData;",
            at = @At("RETURN"))
    private static void electromagnetism$restoreIdentities(ServerLevel level, CompoundTag tag,
            HolderLookup.Provider provider, CallbackInfoReturnable<DevicesSavedData> cir) {
        var devices = cir.getReturnValue();
        for (Tag entry : tag.getList("Devices", Tag.TAG_COMPOUND)) {
            var envelope = (CompoundTag)entry;
            var saved = envelope.get("ElectromagnetismIdentity");
            if(saved==null)saved=net.minecraft.nbt.StringTag.valueOf("Missing persisted native device identity");
            var device = devices.getDevice(DevicesSavedData.readBlockPos(envelope, "Pos"));
            if (device instanceof CeeDeviceIdentity identity)
                identity.electromagnetism$restoreIdentity(saved);
            if(device instanceof com.electromagnetism.integration.cee.nativebridge.NativeEnergyMeter meter)
                meter.electromagnetism$validatePersistedCounter(envelope.getCompound("Data"));
        }
    }
}
