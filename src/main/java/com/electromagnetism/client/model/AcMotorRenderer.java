package com.electromagnetism.client.model;

import com.george_vi.electroenergetics.CEEPartialModels;
import com.electromagnetism.content.electromechanics.AcMotorBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.state.BlockState;

public class AcMotorRenderer extends KineticBlockEntityRenderer<AcMotorBlockEntity> {
    public AcMotorRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected SuperByteBuffer getRotatedModel(AcMotorBlockEntity motor, BlockState state) {
        return CachedBuffers.partialFacing(CEEPartialModels.ELECTRIC_MOTOR_SHAFT, state);
    }
}
