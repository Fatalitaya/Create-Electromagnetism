package com.electromagnetism.content.electronics.mosfet;

import com.electromagnetism.core.model.*;
import com.george_vi.electroenergetics.devices.device.DevicesSavedData;
import com.george_vi.electroenergetics.devices.device.SimulatedDeviceType;
import com.electromagnetism.integration.cee.nativebridge.NativeElectricalDevice;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public class MosfetDevice extends NativeElectricalDevice {

    public double vth = 2;

    public double rdsOn = 0.1;

    public double idMax = 10;

    public boolean pChannel;

    public MosfetDevice(Level level, BlockPos pos, DevicesSavedData deviceSD, SimulatedDeviceType<?> type) {
        super(level, pos, deviceSD, type);
    }

    @Override
    public void read(CompoundTag tag) {
        super.read(tag);
        vth = tag.contains("Vth") ? ElectricalLimits.GATE_THRESHOLD.sanitize(tag.getDouble("Vth"))
                : ElectricalLimits.GATE_THRESHOLD.defaultValue();
        rdsOn = tag.contains("RdsOn") ? ElectricalLimits.ON_RESISTANCE.sanitize(tag.getDouble("RdsOn"))
                : ElectricalLimits.ON_RESISTANCE.defaultValue();
        idMax = tag.contains("IdMax") ? ElectricalLimits.DRAIN_CURRENT.sanitize(tag.getDouble("IdMax"))
                : ElectricalLimits.DRAIN_CURRENT.defaultValue();
    }

    @Override
    public void write(CompoundTag tag) {
        super.write(tag);
        tag.putDouble("Vth", vth);
        tag.putDouble("RdsOn", rdsOn);
        tag.putDouble("IdMax", idMax);
    }

    @Override
    public boolean shouldRemove(BlockState oldState, BlockState newState) {
        return !(newState.getBlock() instanceof MosfetBlock block) || block.isPChannel() != pChannel;
    }
}
