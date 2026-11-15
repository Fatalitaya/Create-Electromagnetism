package com.electromagnetism.integration.cee.mixin;
import com.electromagnetism.integration.cee.nativebridge.NativeEnergyDisplay;
import com.george_vi.electroenergetics.content.energy_meter.EnergyMeterBlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.HolderLookup;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=EnergyMeterBlockEntity.class,remap=false)
public abstract class NativeEnergyMeterDisplayMixin implements NativeEnergyDisplay {
    @Unique private boolean electromagnetism$available;
    @Inject(method="<init>",at=@At("RETURN"))
    private void electromagnetism$initial(CallbackInfo ci) {
        var self=(EnergyMeterBlockEntity)(Object)this;self.activePower=Double.NaN;self.totalEnergy=Double.NaN;
    }
    @Override public void electromagnetism$acceptEnergy(double watts,double netJoules) {
        var self=(EnergyMeterBlockEntity)(Object)this;boolean wasAvailable=electromagnetism$available;
        electromagnetism$available=true;self.activePower=watts;self.setTotalEnergy(netJoules/3_600_000.0);
        if(!wasAvailable)self.sendData();
    }
    @Override public void electromagnetism$energyUnavailable() {
        var self=(EnergyMeterBlockEntity)(Object)this;boolean changed=electromagnetism$available;
        electromagnetism$available=false;self.activePower=Double.NaN;self.totalEnergy=Double.NaN;
        self.smoothTotalEnergy=net.createmod.catnip.animation.LerpedFloat.linear();
        if(changed)self.sendData();
    }
    @Inject(method="read",at=@At("HEAD"))
    private void electromagnetism$prepare(CompoundTag tag,HolderLookup.Provider registries,boolean clientPacket,CallbackInfo ci) {
        var self=(EnergyMeterBlockEntity)(Object)this;
        if(!Double.isFinite(self.totalEnergy)){self.totalEnergy=-1;self.smoothTotalEnergy=net.createmod.catnip.animation.LerpedFloat.linear();}
    }
    @Inject(method="read",at=@At("TAIL"))
    private void electromagnetism$read(CompoundTag tag,HolderLookup.Provider registries,boolean clientPacket,CallbackInfo ci) {
        electromagnetism$available=clientPacket && tag.getBoolean("NativeAvailable");
        if(!electromagnetism$available) {
            var self=(EnergyMeterBlockEntity)(Object)this;self.activePower=Double.NaN;self.totalEnergy=Double.NaN;
            self.smoothTotalEnergy=net.createmod.catnip.animation.LerpedFloat.linear();
        }
    }
    @Inject(method="write",at=@At("TAIL"))
    private void electromagnetism$write(CompoundTag tag,HolderLookup.Provider registries,boolean clientPacket,CallbackInfo ci) {
        if(clientPacket)tag.putBoolean("NativeAvailable",electromagnetism$available);
    }
}
