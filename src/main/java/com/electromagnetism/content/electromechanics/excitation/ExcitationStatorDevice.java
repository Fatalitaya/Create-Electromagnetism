package com.electromagnetism.content.electromechanics.excitation;

import com.electromagnetism.config.ModConfigs;
import com.george_vi.electroenergetics.devices.device.DevicesSavedData;
import com.george_vi.electroenergetics.devices.device.SimulatedDeviceType;
import com.electromagnetism.integration.cee.nativebridge.NativeElectricalDevice;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public class ExcitationStatorDevice extends NativeElectricalDevice {

    public ExcitationStatorDevice(Level level, BlockPos pos, DevicesSavedData deviceSD, SimulatedDeviceType<?> type) {
        super(level, pos, deviceSD, type);
    }

    @Override
    public boolean shouldRemove(BlockState oldState, BlockState newState) {
        return !(newState.getBlock() instanceof ExcitationStatorBlock);
    }
    @Override protected java.util.Map<String, Double> nativeParameters() {
        return java.util.Map.of("resistanceOhms", (double)ModConfigs.server().excitationStatorResistance.getF());
    }
}
