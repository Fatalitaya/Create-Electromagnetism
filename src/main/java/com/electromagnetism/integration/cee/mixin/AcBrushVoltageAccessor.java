package com.electromagnetism.integration.cee.mixin;
import com.george_vi.electroenergetics.content.rotor.AlternatorBrushesBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(value=AlternatorBrushesBlockEntity.class,remap=false)
public interface AcBrushVoltageAccessor {
    @Accessor("voltage") void electromagnetism$voltage(float rms);
}
