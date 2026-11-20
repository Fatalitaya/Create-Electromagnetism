package com.electromagnetism.integration.cee.display;

import com.electromagnetism.integration.cee.mixin.SynchroscopeBlockEntityAccessor;
import com.george_vi.electroenergetics.content.synchroscope.SynchroscopeBlockEntity;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.source.NumericSingleLineDisplaySource;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Map phase difference to a twelve-hour dial. One cycle is twelve hours, or two minutes per
 * degree.
 */
public class SynchroscopeDisplaySource extends NumericSingleLineDisplaySource {
    @Override
    protected MutableComponent provideLine(DisplayLinkContext context, DisplayTargetStats stats) {
        if (!(context.getSourceBlockEntity() instanceof SynchroscopeBlockEntity be))
            return ZERO.copy();
        SynchroscopeBlockEntityAccessor accessor = (SynchroscopeBlockEntityAccessor) be;
        if (!accessor.electromagnetism$isValidConnection())
            return ZERO.copy();

        float phaseOffset = accessor.electromagnetism$getPhaseOffset();
        float totalMinutes = ((phaseOffset % 360) + 360) % 360 * 2f;
        int hours = (int) (totalMinutes / 60);
        if (hours == 0)
            hours = 12;
        int minutes = (int) (totalMinutes % 60);
        return Component.literal(hours + " h " + minutes + " min");
    }

    @Override
    protected boolean allowsLabeling(DisplayLinkContext context) {
        return true;
    }
}
