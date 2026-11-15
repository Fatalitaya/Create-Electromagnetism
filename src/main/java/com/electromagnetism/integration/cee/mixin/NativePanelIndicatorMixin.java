package com.electromagnetism.integration.cee.mixin;
import com.electromagnetism.integration.cee.nativebridge.NativePanelIndicator;
import com.george_vi.electroenergetics.content.electrical_panel.attachments.IndicatorBulbPanelAttachment;
import org.spongepowered.asm.mixin.*;
@Mixin(value=IndicatorBulbPanelAttachment.class,remap=false)
public abstract class NativePanelIndicatorMixin implements NativePanelIndicator {
    @Shadow private float light;
    @Override public void electromagnetism$light(float value){light=value;((IndicatorBulbPanelAttachment)(Object)this).sendData();}
}
