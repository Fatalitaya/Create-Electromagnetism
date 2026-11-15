package com.electromagnetism.integration.cee.mixin;
import com.electromagnetism.integration.cee.nativebridge.NativeHeaterDisplay;
import com.electromagnetism.simulation.SimulationProtocol.Reading;
import com.george_vi.electroenergetics.content.resistive_heater.*;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.HolderLookup;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=ResistiveHeaterBlockEntity.class,remap=false)
public abstract class NativeHeaterDisplayMixin implements NativeHeaterDisplay {
    @Unique private boolean electromagnetism$available;
    @Inject(method="setVoltage",at=@At("HEAD"),cancellable=true)
    private void electromagnetism$rejectLegacyHeat(float voltage,CallbackInfo ci) {
        throw com.electromagnetism.simulation.NativeSimulation.legacyCall("ResistiveHeaterBlockEntity.setVoltage");
    }
    @Override public void electromagnetism$acceptHeat(Reading reading) {
        if(reading.temperatureKelvin().isEmpty()){electromagnetism$heatUnavailable();return;}
        var self=(ResistiveHeaterBlockEntity)(Object)this;
        float heat=(float)Math.clamp((reading.temperatureKelvin().getAsDouble()-293.15)/600,0,1);
        boolean changed=!electromagnetism$available || Math.abs(self.heat-heat)>0.005;
        electromagnetism$available=true;self.heat=heat;self.voltage=(float)reading.rmsVoltage();
        electromagnetism$applyBlock();if(changed)self.sendData();
    }
    @Unique private void electromagnetism$applyBlock() {
        var self=(ResistiveHeaterBlockEntity)(Object)this;var level=self.getLevel();
        if(level==null || level.isClientSide)return;
        var state=self.getBlockState();if(!(state.getBlock() instanceof ResistiveHeaterBlock))return;
        var heat=electromagnetism$available?self.calculateHeatLevel(self.heat):BlazeBurnerBlock.HeatLevel.NONE;
        if(state.getValue(ResistiveHeaterBlock.HEAT_LEVEL)!=heat || state.getValue(ResistiveHeaterBlock.LIT)!=(heat!=BlazeBurnerBlock.HeatLevel.NONE))
            level.setBlockAndUpdate(self.getBlockPos(),state.setValue(ResistiveHeaterBlock.HEAT_LEVEL,heat).setValue(ResistiveHeaterBlock.LIT,heat!=BlazeBurnerBlock.HeatLevel.NONE));
    }
    @Override public void electromagnetism$heatUnavailable() {
        var self=(ResistiveHeaterBlockEntity)(Object)this;boolean changed=electromagnetism$available;
        electromagnetism$available=false;self.heat=0;self.voltage=Float.NaN;electromagnetism$applyBlock();if(changed)self.sendData();
    }
    @Inject(method="read",at=@At("TAIL"))
    private void electromagnetism$read(CompoundTag tag,HolderLookup.Provider registries,boolean clientPacket,CallbackInfo ci) {
        electromagnetism$available=clientPacket && tag.getBoolean("NativeAvailable");
        if(!electromagnetism$available) {
            var self=(ResistiveHeaterBlockEntity)(Object)this;self.heat=0;self.voltage=Float.NaN;
            self.smoothHeat=net.createmod.catnip.animation.LerpedFloat.linear();
        }
    }
    @Inject(method="write",at=@At("TAIL"))
    private void electromagnetism$write(CompoundTag tag,HolderLookup.Provider registries,boolean clientPacket,CallbackInfo ci){if(clientPacket)tag.putBoolean("NativeAvailable",electromagnetism$available);}
}
