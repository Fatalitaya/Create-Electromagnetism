package com.electromagnetism.integration.cee.display;

import com.george_vi.electroenergetics.content.gauge.ElectricGaugeBlock;
import com.simibubi.create.content.kinetics.gauge.GaugeBlock;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Place the two range controls on the faces exposed by CEE. */
public class GaugeControlSlot extends ValueBoxTransform.Sided {
    private final int x;

    public GaugeControlSlot(int x) {
        this.x = x;
    }

    @Override
    protected Vec3 getSouthLocation() {
        return VecHelper.voxelSpace(x, 8, 14);
    }

    @Override
    protected boolean isSideActive(BlockState state, Direction side) {
        Direction facing = state.getValue(ElectricGaugeBlock.FACING);
        if (facing.getAxis().isVertical())
            return side == facing;
        boolean alongFirst = state.getValue(GaugeBlock.AXIS_ALONG_FIRST_COORDINATE);
        if (alongFirst == (facing.getAxis() == Direction.Axis.Z))
            return side.getAxis().isVertical();
        return side.getAxis().isHorizontal() && side.getAxis() != facing.getAxis();
    }
}
