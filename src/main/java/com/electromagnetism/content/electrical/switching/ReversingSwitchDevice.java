package com.electromagnetism.content.electrical.switching;

import com.electromagnetism.config.ModConfigs;
import com.george_vi.electroenergetics.content.cut_off_switch.CutOffSwitchBlock;
import com.george_vi.electroenergetics.devices.device.DevicesSavedData;
import com.george_vi.electroenergetics.devices.device.SimulatedDeviceType;
import com.electromagnetism.integration.cee.nativebridge.NativeElectricalDevice;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public class ReversingSwitchDevice extends NativeElectricalDevice {

    public boolean closed;

    public ReversingSwitchDevice(Level level, BlockPos pos, DevicesSavedData deviceSD, SimulatedDeviceType<?> type) {
        super(level, pos, deviceSD, type);
    }

    @Override
    public void read(CompoundTag tag) {
        super.read(tag);
        closed = tag.getBoolean("Closed");
    }

    @Override
    public void write(CompoundTag tag) {
        super.write(tag);
        tag.putBoolean("Closed", closed);
    }

    @Override
    public boolean shouldRemove(BlockState oldState, BlockState newState) {
        return !(newState.getBlock() instanceof ReversingSwitchBlock);
    }
    @Override protected int nativePortCount() { return 4; }
    @Override protected java.util.Map<String, Double> nativeParameters() {
        if (!level.isLoaded(pos)) throw new IllegalStateException("Switch is not loaded");
        boolean reversed = level.getBlockState(pos).getValue(CutOffSwitchBlock.CLOSED);
        return java.util.Map.of("reversed",reversed?1.0:0.0,"contactResistanceOhms",(double)ModConfigs.server().reversingSwitchResistance.getF());
    }
}
