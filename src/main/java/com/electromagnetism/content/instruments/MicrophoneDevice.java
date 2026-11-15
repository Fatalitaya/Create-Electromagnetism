package com.electromagnetism.content.instruments;
import com.electromagnetism.integration.cee.nativebridge.NativeElectricalDevice;
import com.george_vi.electroenergetics.devices.device.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import java.util.Map;

/** World parameters only; waveform generation and measurement belong to native. */
public class MicrophoneDevice extends NativeElectricalDevice {
    public boolean audioAvailable;
    public MicrophoneDevice(Level level, BlockPos pos, DevicesSavedData sd, SimulatedDeviceType<?> type) { super(level,pos,sd,type); }
    @Override protected Map<String, Double> nativeParameters() { return Map.of("sourceResistanceOhms", 600.0, "sampleRateHz", 16000.0); }
    @Override public boolean shouldRemove(BlockState oldState, BlockState next) { return !(next.getBlock() instanceof MicrophoneBlock); }
}
