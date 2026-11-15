package com.electromagnetism.content.instruments;
import com.electromagnetism.integration.cee.nativebridge.NativeElectricalDevice;
import com.george_vi.electroenergetics.devices.device.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import java.util.Map;

/** World parameters only; waveform generation and measurement belong to native. */
public class SignalGeneratorDevice extends NativeElectricalDevice {
    public SignalGeneratorDevice(Level level, BlockPos pos, DevicesSavedData sd, SimulatedDeviceType<?> type) { super(level,pos,sd,type); }
    @Override protected Map<String, Double> nativeParameters() { if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof SignalGeneratorBlockEntity be) {
            var s = be.settings();
            return Map.of("waveform", (double)s.wave().ordinal(), "frequencyHz", s.frequency(),
                    "amplitudeVolts", s.amplitude(), "offsetVolts", s.offset(), "enabled", s.enabled() ? 1.0 : 0.0,
                    "sourceResistanceOhms", 50.0);
        }
        throw new IllegalStateException("Signal generator settings are not loaded"); }
    @Override public boolean shouldRemove(BlockState oldState, BlockState next) { return !(next.getBlock() instanceof SignalGeneratorBlock); }
}
