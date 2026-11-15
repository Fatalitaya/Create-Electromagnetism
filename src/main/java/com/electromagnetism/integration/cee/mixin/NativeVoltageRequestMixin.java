package com.electromagnetism.integration.cee.mixin;

import com.george_vi.electroenergetics.simulation.RequestVoltageDataPacket;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replace legacy array reads with an explicit-reference native probe. */
@Mixin(value = RequestVoltageDataPacket.class, remap = false)
public abstract class NativeVoltageRequestMixin {
    @Inject(method = "handle(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"), cancellable = true)
    private void electromagnetism$unavailable(ServerPlayer player, CallbackInfo ci) {
        ci.cancel();
        var sd=com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData.load(player.serverLevel());
        ((com.electromagnetism.integration.cee.nativebridge.CeeSimulationAccess)sd.ticker).electromagnetism$probe(player,((RequestVoltageDataPacket)(Object)this).node());
    }
}
