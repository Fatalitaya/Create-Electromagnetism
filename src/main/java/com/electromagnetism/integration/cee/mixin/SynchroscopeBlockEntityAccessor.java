package com.electromagnetism.integration.cee.mixin;

import com.george_vi.electroenergetics.content.synchroscope.SynchroscopeBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read phase offset and connection validity before converting to clock time for the display link. */
@Mixin(SynchroscopeBlockEntity.class)
public interface SynchroscopeBlockEntityAccessor {
    @Accessor(value="phaseOffset",remap=false)
    void electromagnetism$setPhaseOffset(float degrees);
    @Accessor("phaseOffset")
    float electromagnetism$getPhaseOffset();

    @Accessor("validConnection")
    boolean electromagnetism$isValidConnection();
}
