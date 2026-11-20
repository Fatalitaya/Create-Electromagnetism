package com.electromagnetism.integration.cee.mixin;
import com.electromagnetism.integration.cee.nativebridge.NativeEnergyMeter;
import com.george_vi.electroenergetics.content.energy_meter.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
/** After CEE checks ownership, apply the reset at the accepted metering boundary. */
@Mixin(value=ChangeEnergyMeterStatePacket.class,remap=false)
public abstract class NativeEnergyMeterResetMixin {
    @Redirect(method="handle",at=@At(value="FIELD",target="Lcom/george_vi/electroenergetics/content/electrical_panel/attachments/EnergyMeterAttachment;totalEnergy:D",opcode=181))
    private void electromagnetism$panel(com.george_vi.electroenergetics.content.electrical_panel.attachments.EnergyMeterAttachment meter,double ignored){((NativeEnergyMeter)meter).electromagnetism$resetCounter();}
    @Redirect(method="handle",at=@At(value="FIELD",target="Lcom/george_vi/electroenergetics/content/energy_meter/EnergyMeterDevice;totalEnergy:D",opcode=181))
    private void electromagnetism$single(EnergyMeterDevice device,double ignored){((NativeEnergyMeter)device).electromagnetism$resetCounter();}
    @Redirect(method="handle",at=@At(value="FIELD",target="Lcom/george_vi/electroenergetics/content/energy_meter/TriPolarEnergyMeterDevice;totalEnergy:D",opcode=181))
    private void electromagnetism$three(TriPolarEnergyMeterDevice device,double ignored){((NativeEnergyMeter)device).electromagnetism$resetCounter();}
}
