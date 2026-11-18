package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.NativeSimulation;
import com.george_vi.electroenergetics.devices.device.*;
import com.george_vi.electroenergetics.foundation.device.SimpleElectricalDevice;
import com.george_vi.electroenergetics.simulation.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;

/** Lifecycle shell. CeeNativeWorld and its adapters are the sole native model capture path.
 * Legacy state is preserved opaquely, never advanced or unit-converted. */
public abstract class NativeElectricalDevice extends SimpleElectricalDevice {
    private CompoundTag legacyState = new CompoundTag();
    protected NativeElectricalDevice(Level level, BlockPos pos, DevicesSavedData sd, SimulatedDeviceType<?> type) {
        super(level, pos, sd, type);
    }
    @Override public final void preTick(BridgeCollector bridges) { throw NativeSimulation.legacyCall("preTick"); }
    @Override public final void postTick(SimulationResults results) { throw NativeSimulation.legacyCall("postTick"); }
    @Override public void read(CompoundTag tag) { legacyState = tag.copy(); }
    @Override public void write(CompoundTag tag) { tag.merge(legacyState.copy()); }
}
