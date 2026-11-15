package com.electromagnetism.integration.cee.mixin;

import com.electromagnetism.integration.cee.nativebridge.NativePanelPart;
import com.george_vi.electroenergetics.content.electrical_panel.ElectricalPanelDevice;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=ElectricalPanelDevice.class,remap=false)
public abstract class NativePanelPersistenceMixin {
    @Inject(method="read",at=@At("RETURN"))
    private void electromagnetism$read(CompoundTag tag,CallbackInfo ci){
        var parts=((ElectricalPanelDevice)(Object)this).attachments;
        for(int i=0;i<parts.length;i++)if(parts[i]!=null)((NativePanelPart)parts[i]).electromagnetism$readPart(tag.getCompound("AttachmentSlot"+i));
    }
    @Inject(method="write",at=@At("RETURN"))
    private void electromagnetism$write(CompoundTag tag,CallbackInfo ci){
        var parts=((ElectricalPanelDevice)(Object)this).attachments;
        for(int i=0;i<parts.length;i++)if(parts[i]!=null)((NativePanelPart)parts[i]).electromagnetism$writePart(tag.getCompound("AttachmentSlot"+i));
    }
}
