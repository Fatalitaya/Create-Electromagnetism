package com.electromagnetism.integration.create.processing;

import com.electromagnetism.content.thermal.heater.HeatingCoilBlockEntity;

import com.simibubi.create.content.kinetics.fan.processing.AllFanProcessingTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Use Create's blasting recipes when airflow crosses a coil in the blasting temperature range. */
public class HeaterBlastingType extends AllFanProcessingTypes.BlastingType {
    @Override
    public int getPriority() {
        return 150;
    }

    @Override
    public boolean isValidAt(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof HeatingCoilBlockEntity coil)
            return coil.getState() == HeatingCoilBlockEntity.State.BLASTING;
        return false;
    }
}
