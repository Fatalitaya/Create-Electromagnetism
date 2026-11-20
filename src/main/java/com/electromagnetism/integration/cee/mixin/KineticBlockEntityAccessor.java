package com.electromagnetism.integration.cee.mixin;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Create replays lastStressApplied when rebuilding the kinetic network. Update that cache when
 * applying stress.
 */
@Mixin(value = KineticBlockEntity.class, remap = false)
public interface KineticBlockEntityAccessor {
    @Accessor("lastStressApplied")
    void electromagnetism$setLastStressApplied(float value);

    @Accessor("lastStressApplied")
    float electromagnetism$getLastStressApplied();
}
