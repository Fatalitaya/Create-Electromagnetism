package com.electromagnetism.integration.cee.mixin;

import com.electromagnetism.integration.cee.display.MeterReading;
import com.electromagnetism.integration.cee.nativebridge.NativeClampMeter;
import com.george_vi.electroenergetics.CEEDataComponents;
import com.george_vi.electroenergetics.content.clamp_meter.ClampMeterItem;
import com.george_vi.electroenergetics.content.linemans_stick.LinemansStickItem;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value={ClampMeterItem.class,LinemansStickItem.class})
public abstract class NativeClampMeterMixin {
    @Inject(method="onUseTick",at=@At("HEAD"),cancellable=true)
    private void electromagnetism$measure(Level level,LivingEntity entity,ItemStack stack,int remaining,CallbackInfo ci) {
        if(!(level instanceof ServerLevel server))return;
        ci.cancel();
        if(!(entity instanceof Player player))return;
        if(stack.getItem() instanceof LinemansStickItem && !(player.getOffhandItem().getItem() instanceof ClampMeterItem)){player.stopUsingItem();return;}
        var reading=NativeClampMeter.read(server,stack.get(CEEDataComponents.NODE_CONNECTION));
        double amperes=reading.map(r->r.rmsCurrent()).orElse(Double.NaN);
        player.displayClientMessage(Component.translatable("electroenergetics.clamp_meter.current").append(": ").append(MeterReading.automatic(amperes,"A")),true);
    }
    @Inject(method="onStopUsing",at=@At("HEAD"),remap=false)
    private void electromagnetism$clear(ItemStack stack,LivingEntity entity,int count,CallbackInfo ci) {
        if(entity instanceof Player player && !player.level().isClientSide)player.displayClientMessage(Component.empty(),true);
    }
}
