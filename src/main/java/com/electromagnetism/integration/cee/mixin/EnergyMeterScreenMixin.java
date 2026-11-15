package com.electromagnetism.integration.cee.mixin;

import com.electromagnetism.integration.cee.display.MeterReading;
import com.george_vi.electroenergetics.content.energy_meter.EnergyMeterBlockEntity;
import com.george_vi.electroenergetics.content.energy_meter.EnergyMeterScreen;
import net.createmod.catnip.gui.AbstractSimiScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = EnergyMeterScreen.class, remap = false)
public abstract class EnergyMeterScreenMixin extends AbstractSimiScreen {
    @Shadow EnergyMeterBlockEntity be;
    protected EnergyMeterScreenMixin(Component title) { super(title); }

    @Inject(method="renderWindow",at=@At("HEAD"),cancellable=true)
    private void electromagnetism$unavailable(GuiGraphics graphics,int mouseX,int mouseY,float partialTicks,CallbackInfo ci) {
        if(!Double.isFinite(be.totalEnergy) || !Double.isFinite(be.activePower)) {
            graphics.drawString(font,Component.translatable("electromagnetism.simulation.unavailable"),guiLeft+20,guiTop+62,0xFFFFEE,false);ci.cancel();
        }
    }
    @Inject(method = "renderWindow", at = @At("TAIL"))
    private void electromagnetism$units(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        graphics.drawString(font, "kWh", guiLeft + 26, guiTop + 62, 0xFFFFEE);
        graphics.drawString(font, MeterReading.automatic(be.totalEnergy * 1000, "Wh"), guiLeft + 26, guiTop + 94, 0x202020, false);
    }
}
