package com.electromagnetism.content.electronics.mosfet;

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
 * Controls beside the pins edit threshold voltage, on-resistance and rated drain current for the
 * server-side device.
 */
public class MosfetBlockEntity extends SmartBlockEntity {
    protected MosfetScrollValues.Vth vth;
    protected MosfetScrollValues.RdsOn rdsOn;
    protected MosfetScrollValues.IdMax idMax;

    public MosfetBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        vth = new MosfetScrollValues.Vth(
                Component.translatable("electromagnetism.gui.mosfet.vth"), this, new ParamSlot(0));
        vth.withCallback(i -> updateMosfet());
        behaviours.add(vth);

        rdsOn = new MosfetScrollValues.RdsOn(
                Component.translatable("electromagnetism.gui.mosfet.rdson"), this, new ParamSlot(1));
        rdsOn.withCallback(i -> updateMosfet());
        behaviours.add(rdsOn);

        idMax = new MosfetScrollValues.IdMax(
                Component.translatable("electromagnetism.gui.mosfet.idmax"), this, new ParamSlot(2));
        idMax.withCallback(i -> updateMosfet());
        behaviours.add(idMax);
    }

    public double getVth() {
        return vth.getVth();
    }

    public double getRdsOn() {
        return rdsOn.getRdsOn();
    }

    public double getIdMax() {
        return idMax.getIdMax();
    }

    private void updateMosfet() {
        if (!(level instanceof ServerLevel serverLevel))
            return;
        MosfetDevice device = DevicesSavedData.load(serverLevel).getDevice(worldPosition, MosfetDevice.class);
        if (device != null) {
            device.vth = getVth();
            device.rdsOn = getRdsOn();
            device.idMax = getIdMax();
        }
    }

    /**
     * Align the parameter labels horizontally with their pins and leave clearance above the model
     * for interaction.
     */
    private static final NodeConfigurator SLOTS =
            new NodeConfigurator.Builder()
                    .add(13f, 3f, 8f)
                    .add(3f, 3f, 5f)
                    .add(3f, 3f, 11f)
                    .simple(Direction.UP);

    /**
     * Apply the terminal layout's local roll to the parameter boxes before rotating to the
     * mounting face.
     */
    public static Vec3 paramOffset(BlockState state, int index) {
        boolean roll = state.getValue(DirectionalRolledDeviceBlock.ROLL);
        NodeConfigurator rotated = roll ? SLOTS.rotate(new Vec3(0, -90, 0)) : SLOTS;
        return rotated.getNodePos(state.getValue(DirectionalRolledDeviceBlock.FACING), index);
    }

    private static class ParamSlot extends ValueBoxTransform {
        private final int index;

        ParamSlot(int index) {
            this.index = index;
        }

        @Override
        public Vec3 getLocalOffset(LevelAccessor level, BlockPos pos, BlockState state) {
            return paramOffset(state, index);
        }

        /** Labels face up on the floor, down on the ceiling and outward on a wall. */
        @Override
        public void rotate(LevelAccessor level, BlockPos pos, BlockState state, PoseStack ms) {
            Direction facing = state.getValue(DirectionalRolledDeviceBlock.FACING);
            boolean roll = state.getValue(DirectionalRolledDeviceBlock.ROLL);
            float yRot;
            float xRot;
            float zRoll = 0f;
            if (facing.getAxis().isHorizontal()) {
                yRot = AngleHelper.horizontalAngle(facing) + 180f;
                xRot = 0f;
                if (roll)
                    zRoll = 0f;
            } else if (facing == Direction.UP) {
                yRot = 180f;
                xRot = 90f;
            } else {
                yRot = 0f;
                xRot = -90f;
            }
            if (roll && !facing.getAxis().isHorizontal())
                yRot -= 90f;
            TransformStack.of(ms)
                    .rotateYDegrees(yRot)
                    .rotateXDegrees(xRot)
                    .rotateZDegrees(zRoll);
        }
    }
}
