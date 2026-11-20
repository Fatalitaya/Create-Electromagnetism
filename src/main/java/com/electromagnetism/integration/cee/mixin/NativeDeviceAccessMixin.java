package com.electromagnetism.integration.cee.mixin;

import com.electromagnetism.integration.cee.nativebridge.CeeDeviceIdentity;
import com.electromagnetism.simulation.SimulationProtocol.DeviceId;
import com.george_vi.electroenergetics.devices.device.SimulatedDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Placing a replacement at the same position creates a new device identity. */
@Mixin(value = SimulatedDevice.class, remap = false)
public abstract class NativeDeviceAccessMixin implements CeeDeviceIdentity {
    @org.spongepowered.asm.mixin.Shadow private boolean isValid;
    @Override public void electromagnetism$retireTransferredDevice(){isValid=false;}
    @Unique private DeviceId electromagnetism$identity = new DeviceId(UUID.randomUUID(), 0);
    @Unique private Tag electromagnetism$invalidIdentity;
    @Unique private String electromagnetism$identityProblem = "";
    @Override public DeviceId electromagnetism$nativeIdentity() { return electromagnetism$identity; }
    @Override public Tag electromagnetism$saveIdentity() {
        if (electromagnetism$invalidIdentity != null) return electromagnetism$invalidIdentity.copy();
        var tag = new CompoundTag();
        tag.putUUID("Instance", electromagnetism$identity.instance());
        tag.putLong("Generation", electromagnetism$identity.generation());
        return tag;
    }
    @Override public void electromagnetism$restoreIdentity(Tag data) {
        if (data instanceof CompoundTag tag && tag.hasUUID("Instance")
                && tag.contains("Generation", Tag.TAG_LONG) && tag.getLong("Generation") >= 0) {
            electromagnetism$identity = new DeviceId(tag.getUUID("Instance"), tag.getLong("Generation"));
            electromagnetism$invalidIdentity = null;
            electromagnetism$identityProblem = "";
        } else {
            electromagnetism$invalidIdentity = data.copy();
            electromagnetism$identityProblem = "Malformed native device identity; original NBT retained";
        }
    }
    @Override public String electromagnetism$identityProblem() { return electromagnetism$identityProblem; }
}
