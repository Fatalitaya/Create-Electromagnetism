package com.electromagnetism.content.instruments;
import com.electromagnetism.integration.cee.nativebridge.NativeElectricalDevice;
import com.george_vi.electroenergetics.devices.device.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Store radio settings here and keep transient port state in the native session. */
public final class RadioDevice extends NativeElectricalDevice {
    public boolean transmitter;
    public double carrierHz = 8000;
    public RadioDevice(Level level, BlockPos pos, DevicesSavedData sd, SimulatedDeviceType<?> type) { super(level,pos,sd,type); }
    @Override public void read(CompoundTag tag) { super.read(tag); carrierHz = tag.contains("CarrierHz") ? tag.getDouble("CarrierHz") : 8000; }
    @Override public void write(CompoundTag tag) { super.write(tag); tag.putDouble("CarrierHz",carrierHz); }
    @Override public boolean shouldRemove(BlockState oldState, BlockState next) { return !(next.getBlock() instanceof RadioBlock block) || block.transmitter() != transmitter; }
}
