package com.electromagnetism.integration.cee.mixin;
import com.electromagnetism.integration.cee.nativebridge.NativeHvDisplay;
import com.george_vi.electroenergetics.content.transmission_distribution.hv_switch.HVSwitchBlockEntity;
import org.spongepowered.asm.mixin.*;
@Mixin(value=HVSwitchBlockEntity.class,remap=false)
public abstract class NativeHvDisplayMixin implements NativeHvDisplay {
    @Shadow public boolean arcing;
    @Override public void electromagnetism$arc(double temperature,boolean available){boolean active=available&&temperature>=1293.15;if(active!=arcing){arcing=active;((HVSwitchBlockEntity)(Object)this).sendData();}}
}
