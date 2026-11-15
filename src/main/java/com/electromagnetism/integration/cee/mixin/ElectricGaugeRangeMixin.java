package com.electromagnetism.integration.cee.mixin;

import com.electromagnetism.core.model.GaugeRange;
import com.electromagnetism.integration.cee.display.GaugeReading;
import com.george_vi.electroenergetics.content.gauge.ElectricGaugeBlockEntity;
import com.george_vi.electroenergetics.foundation.CEELang;
import com.simibubi.create.foundation.item.TooltipHelper;
import com.simibubi.create.foundation.utility.CreateLang;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(value = ElectricGaugeBlockEntity.class, remap = false)
public abstract class ElectricGaugeRangeMixin implements com.electromagnetism.integration.cee.nativebridge.NativeGauge {
    @org.spongepowered.asm.mixin.Unique private boolean electromagnetism$available;
    @Inject(method="<init>",at=@At("RETURN"))
    private void electromagnetism$initialUnavailable(CallbackInfo ci) {
        ((ElectricGaugeBlockEntity)(Object)this).voltage=Double.NaN;
    }
    @Override public void electromagnetism$acceptGauge(com.electromagnetism.simulation.SimulationProtocol.Reading reading) {
        var self=(ElectricGaugeBlockEntity)(Object)this;
        electromagnetism$available=true;self.voltage=reading.rmsVoltage();
        self.setValue(self.voltmeter?reading.rmsVoltage():reading.rmsCurrent());
    }
    @Override public void electromagnetism$gaugeUnavailable() {
        var self=(ElectricGaugeBlockEntity)(Object)this;
        boolean changed=electromagnetism$available || Double.isFinite(self.voltage);
        electromagnetism$available=false;self.voltage=Double.NaN;self.dialTarget=0;self.redstoneSignal=0;
        if(changed)self.sendData();
    }
    @Inject(method="read",at=@At("TAIL"))
    private void electromagnetism$readAvailability(net.minecraft.nbt.CompoundTag tag,net.minecraft.core.HolderLookup.Provider registries,boolean clientPacket,CallbackInfo ci) {
        electromagnetism$available=clientPacket && tag.getBoolean("NativeAvailable");
        if(!electromagnetism$available) {var self=(ElectricGaugeBlockEntity)(Object)this;self.voltage=Double.NaN;self.dialTarget=0;self.redstoneSignal=0;}
    }
    @Inject(method="write",at=@At("TAIL"))
    private void electromagnetism$writeAvailability(net.minecraft.nbt.CompoundTag tag,net.minecraft.core.HolderLookup.Provider registries,boolean clientPacket,CallbackInfo ci) {
        if(clientPacket)tag.putBoolean("NativeAvailable",electromagnetism$available);
    }

    @Inject(method = "setValue", at = @At("HEAD"), cancellable = true)
    private void electromagnetism$range(double value, CallbackInfo ci) {
        var self = (ElectricGaugeBlockEntity) (Object) this;
        self.dialTarget = Double.isFinite(value)?GaugeRange.dial(value, self.voltmeter, self.scaling.getScale()):0;
        self.sendData();
        ci.cancel();
    }

    @Inject(method = "updateScale", at = @At("TAIL"))
    private void electromagnetism$refreshRange(CallbackInfo ci) {
        var self = (ElectricGaugeBlockEntity) (Object) this;
        if (self.getLevel() != null && !self.getLevel().isClientSide())
            self.setValue(GaugeReading.measurement(self));
    }

    @Inject(method = "addToGoggleTooltip", at = @At("HEAD"), cancellable = true)
    private void electromagnetism$reading(List<Component> tooltip, boolean sneaking,
                                         CallbackInfoReturnable<Boolean> cir) {
        var self = (ElectricGaugeBlockEntity) (Object) this;
        CreateLang.translate("gui.gauge.info_header").forGoggles(tooltip);
        CEELang.translate(self.voltmeter ? "generic.voltage" : "generic.current")
                .style(ChatFormatting.GRAY).forGoggles(tooltip);
        if(!Double.isFinite(self.voltage)) {
            CEELang.builder().add(GaugeReading.format(self)).style(ChatFormatting.GRAY).forGoggles(tooltip);
            cir.setReturnValue(true);return;
        }
        float dial = self.dialState;
        ChatFormatting color = dial < 0.01 ? ChatFormatting.DARK_GRAY
                : dial < 0.33 ? ChatFormatting.GREEN
                : dial < 0.66 ? (self.voltmeter ? ChatFormatting.AQUA : ChatFormatting.GOLD)
                : (self.voltmeter ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.RED);
        CEELang.builder().text(TooltipHelper.makeProgressBar(3,
                        dial < 0.01 ? 0 : dial < 0.33 ? 1 : dial < 0.66 ? 2 : 3))
                .space().add(GaugeReading.format(self)).style(color).forGoggles(tooltip);
        cir.setReturnValue(true);
    }
}
