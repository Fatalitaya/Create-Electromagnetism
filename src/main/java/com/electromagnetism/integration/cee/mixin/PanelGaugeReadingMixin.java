package com.electromagnetism.integration.cee.mixin;

import com.electromagnetism.integration.cee.display.MeterReading;
import com.george_vi.electroenergetics.content.electrical_panel.attachments.GaugePanelAttachment;
import com.george_vi.electroenergetics.foundation.CEELang;
import net.createmod.catnip.lang.LangBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(value = GaugePanelAttachment.class, remap = false)
public abstract class PanelGaugeReadingMixin {
    @Redirect(method = "addToGoggleTooltip", at = @At(value = "INVOKE",
            target = "Lcom/simibubi/create/foundation/utility/CreateLang;number(D)Lnet/createmod/catnip/lang/LangBuilder;"))
    private LangBuilder electromagnetism$reading(double ignoredRoundedValue) {
        var self = (GaugePanelAttachment) (Object) this;
        return CEELang.builder().add(MeterReading.automatic(Math.abs(self.value), self.voltmeter ? "V" : "A"));
    }
    @Redirect(method = "addToGoggleTooltip", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/chat/Component;translatable(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;"))
    private MutableComponent electromagnetism$unit(String key) {
        return Component.empty(); // The numeric text already includes the selected range's unit.
    }
}
