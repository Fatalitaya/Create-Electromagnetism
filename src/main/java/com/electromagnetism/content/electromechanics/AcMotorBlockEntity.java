package com.electromagnetism.content.electromechanics;

import com.electromagnetism.config.ModConfigs;
import com.electromagnetism.integration.cee.nativebridge.CeeMechanicalSource;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public class AcMotorBlockEntity extends GeneratingKineticBlockEntity implements CeeMechanicalSource {
    private float generatedRpm;
    private float stressCapacity;

    public AcMotorBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public float electromagnetism$shaftSpeedRpm() {
        return isOverStressed() ? 0 : getSpeed();
    }

    @Override
    public float getGeneratedSpeed() {
        return generatedRpm;
    }

    @Override
    public float calculateAddedStressCapacity() {
        return lastCapacityProvided = stressCapacity;
    }

    public void stopElectricalDrive() {
        acceptElectricalDrive(0, 0);
    }

    public void acceptElectricalDrive(double rpm, double torqueNewtonMetres) {
        float nextCapacity = (float) Math.max(0, torqueNewtonMetres * Math.signum(rpm)
                / ModConfigs.server().nativeTorquePerBaseStress.getF());
        float nextRpm = (float) rpm;
        boolean speedChanged = nextRpm != generatedRpm;
        if (!speedChanged && stressCapacity == nextCapacity) return;
        stressCapacity = nextCapacity;
        generatedRpm = nextRpm;
        if (speedChanged) updateGeneratedRotation();
        if (hasNetwork()) notifyStressCapacityChange(calculateAddedStressCapacity());
        sendData();
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (clientPacket) {
            tag.putFloat("GeneratedRpm", generatedRpm);
            tag.putFloat("MotorCapacity", stressCapacity);
        }
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        generatedRpm = clientPacket ? tag.getFloat("GeneratedRpm") : 0;
        stressCapacity = clientPacket ? tag.getFloat("MotorCapacity") : 0;
        if (!clientPacket) reActivateSource = true;
    }
}
