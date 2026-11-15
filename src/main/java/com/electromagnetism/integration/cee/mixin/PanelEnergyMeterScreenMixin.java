package com.electromagnetism.integration.cee.mixin;

import com.electromagnetism.integration.cee.display.MeterReading;
import com.george_vi.electroenergetics.content.energy_meter.PanelEnergyMeterScreen;
import com.george_vi.electroenergetics.content.electrical_panel.attachments.BaseEnergyMeterAttachment;
import net.createmod.catnip.gui.AbstractSimiScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = PanelEnergyMeterScreen.class, remap = false)
public abstract class PanelEnergyMeterScreenMixin extends AbstractSimiScreen {
    @Shadow BaseEnergyMeterAttachment be;
    protected PanelEnergyMeterScreenMixin(Component title) { super(title); }
    @Inject(method = "renderWindow", at = @At("TAIL"))
    private void electromagnetism$units(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        graphics.drawString(font, "kWh", guiLeft + 26, guiTop + 62, 0xFFFFEE);
        graphics.drawString(font, MeterReading.automatic(be.totalEnergy * 1000, "Wh"), guiLeft + 26, guiTop + 94, 0x202020, false);
    }
}
