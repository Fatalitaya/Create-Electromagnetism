package com.electromagnetism.integration.cee.mixin;

import com.electromagnetism.integration.cee.nativebridge.NativeEnergyMeter;
import com.george_vi.electroenergetics.content.electrical_panel.attachments.BaseEnergyMeterAttachment;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.UUID;

@Mixin(value=BaseEnergyMeterAttachment.class,remap=false)
public abstract class NativePanelMeterMixin implements NativeEnergyMeter {
    @Unique private UUID electromagnetism$counter=UUID.randomUUID();
    @Unique private net.minecraft.nbt.Tag electromagnetism$invalid;
    @Override public UUID electromagnetism$counterIdentity(){return electromagnetism$counter;}
    @Override public String electromagnetism$counterProblem(){return electromagnetism$invalid==null?"":"Malformed panel energy counter";}
    @Override public void electromagnetism$resetCounter(){electromagnetism$counter=UUID.randomUUID();}
    @Override public void electromagnetism$validatePersistedCounter(CompoundTag tag){
        if(tag.contains("NativeCounter")&&!tag.hasUUID("NativeCounter"))electromagnetism$invalid=tag.get("NativeCounter").copy();
    }
    @Inject(method="read",at=@At("TAIL"))
    private void electromagnetism$read(CompoundTag tag,boolean packet,HolderLookup.Provider registries,CallbackInfo ci){
        electromagnetism$validatePersistedCounter(tag);if(tag.hasUUID("NativeCounter"))electromagnetism$counter=tag.getUUID("NativeCounter");
    }
    @Inject(method="write",at=@At("TAIL"))
    private void electromagnetism$write(CompoundTag tag,boolean packet,HolderLookup.Provider registries,CallbackInfo ci){
        if(electromagnetism$invalid!=null)tag.put("NativeCounter",electromagnetism$invalid.copy());else tag.putUUID("NativeCounter",electromagnetism$counter);
    }
}
