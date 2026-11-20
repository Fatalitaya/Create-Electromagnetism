package com.electromagnetism.content.lighting.fixture;

import com.electromagnetism.client.model.ModPartialModels;
import com.electromagnetism.content.lighting.bulb.GrowthLampItem;
import com.electromagnetism.content.lighting.bulb.ILightBulb;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import com.simibubi.create.foundation.render.RenderTypes;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;

/** Draw the shell, tintable glass and glow in order. A burnt bulb skips the glow layer. */
public class LightFixtureRenderer extends SafeBlockEntityRenderer<LightFixtureBlockEntity> {
    public LightFixtureRenderer(BlockEntityRendererProvider.Context context) {
        super();
    }

    @Override
    protected void renderSafe(LightFixtureBlockEntity be, float partialTicks, PoseStack matrices, MultiBufferSource consumer, int light, int overlay) {
        if (!be.hasBulb())
            return;

        BlockState state = be.getBlockState();
        Direction facing = state.getValue(LightFixtureBlock.FACING);
        Item bulb = be.getBulbItem();
        boolean growth = bulb instanceof GrowthLampItem;
        boolean dyed = be.getColor() != null && bulb instanceof ILightBulb lightBulb
                && lightBulb.thermalProperties() != null && lightBulb.thermalProperties().dyeable();

        PartialModel model = modelFor(be, growth, dyed);
        if (model == null)
            return;

        var vb = consumer.getBuffer(RenderType.cutout());
        rotateToFacing(CachedBuffers.partial(model, state), facing)
                .translate(LightFixtureBlock.BULB_MODEL_OFFSET)
                .light(light)
                .renderInto(matrices, vb);

        int r = 255, g = 255, b = 255;
        DyeColor color = be.getColor();
        if (dyed && color != null) {
            int texDif = color.getTextureDiffuseColor();
            r = (texDif & 0xFF0000) >> 16;
            g = (texDif & 0xFF00) >> 8;
            b = texDif & 0xFF;
            vb = consumer.getBuffer(RenderType.translucent());
            rotateToFacing(CachedBuffers.partial(ModPartialModels.DYED_LIGHT_BULB_BULB, state), facing)
                    .color(r, g, b, 255)
                    .translate(LightFixtureBlock.BULB_MODEL_OFFSET)
                    .light(light)
                    .renderInto(matrices, vb);
        }

        if (be.isBurned())
            return;

        // Use full-bright additive blending for the glow layer with opacity from filament state.
        float a = be.getAlpha();
        if (a > 0) {
            PartialModel lightModel = growth ? ModPartialModels.GROWTH_LAMP_LIGHT
                    : dyed ? ModPartialModels.DYED_LIGHT_BULB_LIGHT
                    : ModPartialModels.LIGHT_BULB_LIGHT;
            rotateToFacing(CachedBuffers.partial(lightModel, state), facing)
                    .translate(LightFixtureBlock.BULB_MODEL_OFFSET)
                    .light(LightTexture.FULL_BRIGHT)
                    .color((int) (a * r), (int) (a * g), (int) (a * b), 255)
                    .disableDiffuse()
                    .renderInto(matrices, consumer.getBuffer(RenderTypes.additive()));
        }
    }

    private PartialModel modelFor(LightFixtureBlockEntity be, boolean growth, boolean dyed) {
        int power = be.getBlockState().hasProperty(LightFixtureBlock.POWER) ? be.getBlockState().getValue(LightFixtureBlock.POWER) : 0;
        if (be.isBurned()) {
            if (growth)
                return ModPartialModels.GROWTH_LAMP_BROKEN;
            return dyed ? ModPartialModels.DYED_LIGHT_BULB_BROKEN : ModPartialModels.LIGHT_BULB_BROKEN;
        }
        if (power >= 1) {
            if (growth)
                return ModPartialModels.GROWTH_LAMP_ON;
            return dyed ? ModPartialModels.DYED_LIGHT_BULB_ON : ModPartialModels.LIGHT_BULB_ON;
        }
        if (growth)
            return ModPartialModels.GROWTH_LAMP;
        return dyed ? ModPartialModels.DYED_LIGHT_BULB : ModPartialModels.LIGHT_BULB;
    }

    public SuperByteBuffer rotateToFacing(SuperByteBuffer buffer, Direction facing) {
        return switch (facing) {
            case UP -> buffer;
            case DOWN -> buffer.rotateCentered((float) Math.PI, Direction.EAST);
            default -> {
                buffer.rotateCentered((float) Math.PI * 0.5f, Direction.EAST);
                yield buffer.rotateCentered((float) ((facing.toYRot()) / 180f * Math.PI), Direction.SOUTH);
            }
        };
    }
}
