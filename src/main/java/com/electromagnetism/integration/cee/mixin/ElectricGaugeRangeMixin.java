package com.electromagnetism.integration.cee.mixin;

import com.electromagnetism.core.model.GaugeRange;
import com.electromagnetism.integration.cee.display.GaugeReading;
import com.electromagnetism.integration.cee.display.GaugeControlSlot;
import com.electromagnetism.integration.cee.display.GaugeModeBehaviour;
import com.electromagnetism.simulation.SimulationProtocol.Reading;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.george_vi.electroenergetics.content.gauge.ElectricGaugeBlockEntity;
import com.george_vi.electroenergetics.foundation.CEELang;
import com.simibubi.create.foundation.item.TooltipHelper;
import com.simibubi.create.foundation.utility.CreateLang;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(value = ElectricGaugeBlockEntity.class, remap = false)
public abstract class ElectricGaugeRangeMixin implements com.electromagnetism.integration.cee.nativebridge.NativeGauge {
    @Unique private GaugeModeBehaviour electromagnetism$mode;
    @Unique private Reading electromagnetism$lastReading;
    @Unique private double electromagnetism$displayValue;

    @ModifyArg(method = "addBehaviours", index = 2, at = @At(value = "INVOKE",
            target = "Lcom/george_vi/electroenergetics/foundation/scroll_value/ScalingScrollValueBehaviour;<init>(Lnet/minecraft/network/chat/Component;Lcom/simibubi/create/foundation/blockEntity/SmartBlockEntity;Lcom/simibubi/create/foundation/blockEntity/behaviour/ValueBoxTransform;)V"))
    private ValueBoxTransform electromagnetism$rangeSlot(ValueBoxTransform original) {
        return new GaugeControlSlot(4);
    }

    @Inject(method = "addBehaviours", at = @At("TAIL"))
    private void electromagnetism$addMode(List<BlockEntityBehaviour> behaviours, CallbackInfo ci) {
        var self = (ElectricGaugeBlockEntity) (Object) this;
        electromagnetism$mode = new GaugeModeBehaviour(self);
        electromagnetism$mode.withCallback(value -> electromagnetism$refreshMeasurement());
        behaviours.add(electromagnetism$mode);
    }

    @Override public double electromagnetism$measurement() {
        return electromagnetism$displayValue;
    }

    @Unique private void electromagnetism$refreshMeasurement() {
        var self = (ElectricGaugeBlockEntity) (Object) this;
        if (electromagnetism$lastReading != null) {
            electromagnetism$displayValue = electromagnetism$mode.measurement(electromagnetism$lastReading, self.voltmeter);
            self.setValue(electromagnetism$displayValue);
        }
    }
    @Inject(method="<init>",at=@At("RETURN"))
    private void electromagnetism$initialUnavailable(CallbackInfo ci) {
        ((ElectricGaugeBlockEntity)(Object)this).voltage=Double.NaN;
        electromagnetism$displayValue = Double.NaN;
    }
    @Override public void electromagnetism$acceptGauge(Reading reading) {
        var self=(ElectricGaugeBlockEntity)(Object)this;
        self.voltage = reading.rmsVoltage();
        electromagnetism$lastReading = reading;
        electromagnetism$refreshMeasurement();
    }
    @Override public void electromagnetism$gaugeUnavailable() {
        var self=(ElectricGaugeBlockEntity)(Object)this;
        boolean changed=Double.isFinite(electromagnetism$displayValue) || Double.isFinite(self.voltage);
        electromagnetism$lastReading = null;
        electromagnetism$displayValue = Double.NaN;
        self.voltage=Double.NaN;self.dialTarget=0;self.redstoneSignal=0;
        if(changed)self.sendData();
    }
    @Inject(method="read",at=@At("TAIL"))
    private void electromagnetism$readAvailability(net.minecraft.nbt.CompoundTag tag,net.minecraft.core.HolderLookup.Provider registries,boolean clientPacket,CallbackInfo ci) {
        electromagnetism$lastReading = null;
        electromagnetism$displayValue = clientPacket && tag.contains("GaugeMeasurement")
                ? tag.getDouble("GaugeMeasurement") : Double.NaN;
        if(!Double.isFinite(electromagnetism$displayValue)) {var self=(ElectricGaugeBlockEntity)(Object)this;self.voltage=Double.NaN;self.dialTarget=0;self.redstoneSignal=0;}
    }
    @Inject(method="write",at=@At("TAIL"))
    private void electromagnetism$writeAvailability(net.minecraft.nbt.CompoundTag tag,net.minecraft.core.HolderLookup.Provider registries,boolean clientPacket,CallbackInfo ci) {
        if (clientPacket && Double.isFinite(electromagnetism$displayValue))
            tag.putDouble("GaugeMeasurement", electromagnetism$displayValue);
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
        if(!Double.isFinite(electromagnetism$displayValue)) {
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
