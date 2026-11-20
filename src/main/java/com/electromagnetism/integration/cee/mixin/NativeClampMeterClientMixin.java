package com.electromagnetism.integration.cee.mixin;

import com.george_vi.electroenergetics.CEEDataComponents;
import com.george_vi.electroenergetics.client.ElectricPropertiesOverlay;
import com.george_vi.electroenergetics.content.clamp_meter.ClampMeterItem;
import com.george_vi.electroenergetics.content.linemans_stick.LinemansStickItem;
import com.george_vi.electroenergetics.content.wire.interaction.InteractWirePacket;
import com.george_vi.electroenergetics.content.wire.interaction.WireInteractionHandler;
import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The client identifies the target. The action bar displays the accepted RMS current from the
 * server.
 */
@Mixin(value={ClampMeterItem.class,LinemansStickItem.class})
public abstract class NativeClampMeterClientMixin {
    @Inject(method="onUseTick",at=@At("HEAD"),cancellable=true)
    private void electromagnetism$target(Level level,LivingEntity entity,ItemStack stack,int remaining,CallbackInfo ci) {
        if(!level.isClientSide)return;
        ci.cancel();
        ElectricPropertiesOverlay.INSTANCE.removeMeter();
        if(!(entity instanceof Player player))return;
        var point=WireInteractionHandler.targetedPoint;
        if(point==null || stack.getItem() instanceof LinemansStickItem && !(player.getOffhandItem().getItem() instanceof ClampMeterItem)){player.stopUsingItem();return;}
        if(!point.connection().equals(stack.get(CEEDataComponents.NODE_CONNECTION)))CatnipServices.NETWORK.sendToServer(new InteractWirePacket(point));
    }
}
