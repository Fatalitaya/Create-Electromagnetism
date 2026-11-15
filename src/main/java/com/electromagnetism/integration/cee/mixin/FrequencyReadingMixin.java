package com.electromagnetism.integration.cee.mixin;

import com.electromagnetism.integration.cee.display.MeterReading;
import com.george_vi.electroenergetics.content.frequency_meter.FrequencyMeterBlockEntity;
import com.george_vi.electroenergetics.foundation.CEELang;
import com.simibubi.create.foundation.utility.CreateLang;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.List;

@Mixin(value = FrequencyMeterBlockEntity.class, remap = false)
public abstract class FrequencyReadingMixin {
    @Inject(method = "addToGoggleTooltip", at = @At("HEAD"), cancellable = true)
    private void electromagnetism$reading(List<Component> tooltip, boolean sneaking, CallbackInfoReturnable<Boolean> cir) {
        double value = ((FrequencyMeterBlockEntityAccessor) this).electromagnetism$getFrequency();
        CreateLang.translate("gui.gauge.info_header").forGoggles(tooltip);
        CEELang.translate("gui.goggles.frequency").style(ChatFormatting.GRAY).forGoggles(tooltip);
        CEELang.builder().add(MeterReading.frequency(value))
                .style(value < 5 ? ChatFormatting.DARK_GRAY : ChatFormatting.AQUA).forGoggles(tooltip);
        cir.setReturnValue(true);
    }
}
