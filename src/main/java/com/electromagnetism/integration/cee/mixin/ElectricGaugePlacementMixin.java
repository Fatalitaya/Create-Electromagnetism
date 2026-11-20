package com.electromagnetism.integration.cee.mixin;

import com.george_vi.electroenergetics.content.gauge.ElectricGaugeBlock;
import com.simibubi.create.content.kinetics.gauge.GaugeBlock;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mount the gauge on an ordinary support. The clicked face and player's view determine
 * orientation.
 */
@Mixin(value = ElectricGaugeBlock.class, remap = false)
public abstract class ElectricGaugePlacementMixin {
    @Inject(method = "getStateForPlacement", at = @At("RETURN"), cancellable = true)
    private void electromagnetism$placeLikeCreateGauge(BlockPlaceContext context,
                                                       CallbackInfoReturnable<BlockState> cir) {
        BlockState state = cir.getReturnValue();
        if (state == null) {
            return;
        }

        Direction facing = context.getClickedFace();
        // Keep terminals horizontal on walls and across the player's view on floors and ceilings.
        boolean alongFirst = facing.getAxis().isVertical()
                ? context.getHorizontalDirection().getAxis() != Direction.Axis.X
                : facing.getAxis() == Direction.Axis.Z;
        cir.setReturnValue(state.setValue(ElectricGaugeBlock.FACING, facing)
                .setValue(GaugeBlock.AXIS_ALONG_FIRST_COORDINATE, alongFirst));
    }
}
