package com.electromagnetism.content.lighting.factory;

import com.electromagnetism.client.model.ModPartialModels;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import com.simibubi.create.foundation.render.RenderTypes;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

/** Render the projected light with translucent beam models. The block model supplies the housing. */
public class FactoryLightRenderer extends SafeBlockEntityRenderer<FactoryLightBlockEntity> {
    public FactoryLightRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    protected void renderSafe(FactoryLightBlockEntity be, float partialTicks, PoseStack matrices, MultiBufferSource consumer, int light, int overlay) {
        if (!be.hasBulb() || be.isBurned())
            return;
        var state = be.getBlockState();
        int part = state.getValue(FactoryLightBlock.PART);
        // X-axis segments reuse the corresponding Z-axis beam rotated a quarter turn.
        int rotation = 0;
        PartialModel lightModel = switch (part) {
            case 0 -> ModPartialModels.FL_RAYS_SINGLE;
            case 1 -> ModPartialModels.FL_RAYS_FRONT;
            case 2 -> ModPartialModels.FL_RAYS_CENTER;
            case 3 -> ModPartialModels.FL_RAYS_BACK;
            case 4 -> {
                rotation = 90;
                yield ModPartialModels.FL_RAYS_FRONT;
            }
            case 5 -> {
                rotation = 90;
                yield ModPartialModels.FL_RAYS_CENTER;
            }
            case 6 -> {
                rotation = 90;
                yield ModPartialModels.FL_RAYS_BACK;
            }
            default -> ModPartialModels.FL_RAYS_SINGLE;
        };
        int a = (int) (be.getAlpha() * 255);
        if (a > 0) {
            CachedBuffers.partial(lightModel, state)
                    .light(LightTexture.FULL_BRIGHT)
                    .rotateYCenteredDegrees(rotation)
                    .color(a, a, a, 255)
                    .disableDiffuse()
                    .renderInto(matrices, consumer.getBuffer(RenderTypes.additive()));
        }
    }
}
