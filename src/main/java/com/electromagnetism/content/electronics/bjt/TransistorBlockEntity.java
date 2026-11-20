package com.electromagnetism.content.electronics.bjt;

import java.util.List;

import com.electromagnetism.registry.ModBlockEntityTypes;
import com.george_vi.electroenergetics.foundation.base.DirectionalRolledDeviceBlock;
import com.george_vi.electroenergetics.devices.device.DevicesSavedData;
import com.george_vi.electroenergetics.foundation.nodes.NodeConfigurator;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;

import net.createmod.catnip.math.AngleHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.engine_room.flywheel.lib.transform.TransformStack;

/**
 * Separate scroll controls edit β, base-emitter turn-on voltage and collector current rating.
 * Changes reach the server-side device.
 */
public class TransistorBlockEntity extends SmartBlockEntity {
    protected TransistorScrollValues.Beta beta;
    protected TransistorScrollValues.Vbe vbe;
    protected TransistorScrollValues.IcMax icMax;

    public TransistorBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        beta = new TransistorScrollValues.Beta(
                Component.translatable("electromagnetism.gui.transistor.beta"), this, new ParamSlot(0));
        beta.withCallback(i -> updateTransistor());
        behaviours.add(beta);

        vbe = new TransistorScrollValues.Vbe(
                Component.translatable("electromagnetism.gui.transistor.vbe"), this, new ParamSlot(1));
        vbe.withCallback(i -> updateTransistor());
        behaviours.add(vbe);

        icMax = new TransistorScrollValues.IcMax(
                Component.translatable("electromagnetism.gui.transistor.icmax"), this, new ParamSlot(2));
        icMax.withCallback(i -> updateTransistor());
        behaviours.add(icMax);
    }

    public double getBeta() {
        return beta.getBeta();
    }

    public double getVbeOn() {
        return vbe.getVbeOn();
    }

    public double getIcMax() {
        return icMax.getIcMax();
    }

    private void updateTransistor() {
        if (!(level instanceof ServerLevel serverLevel))
            return;
        TransistorDevice device = DevicesSavedData.load(serverLevel).getDevice(worldPosition, TransistorDevice.class);
        if (device != null) {
            device.beta = getBeta();
            device.vbeOn = getVbeOn();
            device.icMax = getIcMax();
        }
    }

    /**
     * Slots use the south-facing model as reference. Gain is on top, with voltage and current
     * below on opposite sides.
     */
    private static final NodeConfigurator SLOTS =
            new NodeConfigurator.Builder()
                    .add(8f, 11f, 9.75f)
                    .add(5f, 6f, 9.75f)
                    .add(11f, 6f, 9.75f)
                    .simple(Direction.UP);

    private static class ParamSlot extends ValueBoxTransform {
        private final int index;

        ParamSlot(int index) {
            this.index = index;
        }

        @Override
        public Vec3 getLocalOffset(LevelAccessor level, BlockPos pos, BlockState state) {
            boolean roll = state.getValue(DirectionalRolledDeviceBlock.ROLL);
            NodeConfigurator rotated = roll
                    ? SLOTS.rotate(new Vec3(0, -90, 0))
                    : SLOTS;
            return rotated.getNodePos(state.getValue(DirectionalRolledDeviceBlock.FACING), index);
        }

        /** Apply the same orientation transform to the label face and its slot offset. */
        @Override
        public void rotate(LevelAccessor level, BlockPos pos, BlockState state, PoseStack ms) {
            Direction facing = state.getValue(DirectionalRolledDeviceBlock.FACING);
            boolean roll = state.getValue(DirectionalRolledDeviceBlock.ROLL);
            float yRot;
            float xRot;
            if (facing.getAxis().isHorizontal()) {
                yRot = AngleHelper.horizontalAngle(facing) + 180f;
                xRot = 90f;
            } else if (facing == Direction.UP) {
                yRot = 180f;
                xRot = 0f;
            } else {
                yRot = 0f;
                xRot = 0f;
            }
            if (roll)
                yRot -= 90f;
            TransformStack.of(ms)
                    .rotateYDegrees(yRot)
                    .rotateXDegrees(xRot);
        }
    }
}
