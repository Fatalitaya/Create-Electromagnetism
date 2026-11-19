package com.electromagnetism.content.electronics;

import com.electromagnetism.integration.cee.nativebridge.NativeElectricalDevice;
import com.george_vi.electroenergetics.devices.device.DevicesSavedData;
import com.george_vi.electroenergetics.devices.device.SimulatedDeviceType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public final class PowerChokeDevice extends NativeElectricalDevice {
    public PowerChokeDevice(Level level, BlockPos pos, DevicesSavedData data, SimulatedDeviceType<?> type) {
        super(level, pos, data, type);
    }

    @Override
    public boolean shouldRemove(BlockState oldState, BlockState newState) {
        return !(newState.getBlock() instanceof PowerChokeBlock);
    }
}
