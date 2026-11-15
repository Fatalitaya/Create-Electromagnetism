package com.electromagnetism.integration.cee.mixin;
import com.electromagnetism.integration.cee.nativebridge.NativeEnergyMeter;
import com.george_vi.electroenergetics.content.energy_meter.*;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.UUID;
@Mixin(value={EnergyMeterDevice.class,TriPolarEnergyMeterDevice.class},remap=false)
public abstract class NativeEnergyMeterDeviceMixin implements NativeEnergyMeter {
    @Unique private UUID electromagnetism$counter=UUID.randomUUID();
    @Unique private String electromagnetism$problem="";
    @Unique private net.minecraft.nbt.Tag electromagnetism$invalidCounter;
    @Override public UUID electromagnetism$counterIdentity(){return electromagnetism$counter;}
    @Override public String electromagnetism$counterProblem(){return electromagnetism$problem;}
    @Override public void electromagnetism$resetCounter(){electromagnetism$counter=UUID.randomUUID();}
    @Override public void electromagnetism$validatePersistedCounter(CompoundTag data) {
        if(!data.hasUUID("NativeCounter")) {
            electromagnetism$problem="Missing or malformed persisted native energy counter identity";
            electromagnetism$invalidCounter=data.contains("NativeCounter")?data.get("NativeCounter").copy():net.minecraft.nbt.StringTag.valueOf("missing native counter identity");
        }
    }
    @Inject(method="read",at=@At("TAIL"))
    private void electromagnetism$read(CompoundTag tag,CallbackInfo ci) {
        if(tag.hasUUID("NativeCounter"))electromagnetism$counter=tag.getUUID("NativeCounter");
        else if(tag.contains("NativeCounter")) {
            electromagnetism$problem="Malformed native energy counter identity";
            electromagnetism$invalidCounter=tag.get("NativeCounter").copy();
        }
        if(!tag.getString("NativeCounterProblem").isBlank())electromagnetism$problem=tag.getString("NativeCounterProblem");
    }
    @Inject(method="write",at=@At("TAIL"))
    private void electromagnetism$write(CompoundTag tag,CallbackInfo ci) {
        if(electromagnetism$invalidCounter!=null)tag.put("NativeCounter",electromagnetism$invalidCounter.copy());
        else tag.putUUID("NativeCounter",electromagnetism$counter);
        if(!electromagnetism$problem.isEmpty())tag.putString("NativeCounterProblem",electromagnetism$problem);
    }
}
