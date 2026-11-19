package com.electromagnetism.integration.cee.mixin;

import com.george_vi.electroenergetics.simulation.RequestVoltageDataPacket;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native simulation does not populate the legacy results read by this packet. */
@Mixin(value = RequestVoltageDataPacket.class, remap = false)
public abstract class NativeVoltageRequestMixin {
    @Inject(method = "handle(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"), cancellable = true)
    private void electromagnetism$skipLegacyVoltageRequest(ServerPlayer player, CallbackInfo ci) {
        ci.cancel();
    }
}
