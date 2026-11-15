package com.electromagnetism.integration.cee.mixin;

import com.electromagnetism.integration.cee.nativebridge.NativePanelPart;
import com.george_vi.electroenergetics.content.electrical_panel.attachments.PanelAttachment;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import java.util.UUID;

@Mixin(value=PanelAttachment.class,remap=false)
public abstract class NativePanelPartMixin implements NativePanelPart {
    @Unique private UUID electromagnetism$id=UUID.randomUUID();
    @Unique private boolean electromagnetism$initialized;
    @Unique private net.minecraft.nbt.Tag electromagnetism$invalid;
    @Override public String electromagnetism$partProblem(){return electromagnetism$invalid==null?"":"Malformed panel attachment identity";}
    @Override public void electromagnetism$renewElectricalIdentity(){electromagnetism$id=UUID.randomUUID();}
    @Override public UUID electromagnetism$partId(){return electromagnetism$id;}
    @Override public void electromagnetism$initializePart(){
        if(!electromagnetism$initialized){((PanelAttachment)(Object)this).initialize();electromagnetism$initialized=true;}
    }
    @Override public void electromagnetism$readPart(CompoundTag envelope){
        if(envelope.contains("NativePart") && !envelope.hasUUID("NativePart")){electromagnetism$invalid=envelope.get("NativePart").copy();return;}
        if(envelope.hasUUID("NativePart"))electromagnetism$id=envelope.getUUID("NativePart");
    }
    @Override public void electromagnetism$writePart(CompoundTag envelope){if(electromagnetism$invalid!=null)envelope.put("NativePart",electromagnetism$invalid.copy());else envelope.putUUID("NativePart",electromagnetism$id);}
}
