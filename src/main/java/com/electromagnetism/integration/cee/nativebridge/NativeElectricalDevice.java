package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.NativeSimulation;
import com.electromagnetism.simulation.SimulationProtocol.*;
import com.george_vi.electroenergetics.devices.device.*;
import com.george_vi.electroenergetics.foundation.device.SimpleElectricalDevice;
import com.george_vi.electroenergetics.simulation.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import java.util.Map;
import java.util.stream.IntStream;

/** Parameter/lifecycle shell. Legacy state is preserved opaquely, never advanced or unit-converted. */
public abstract class NativeElectricalDevice extends SimpleElectricalDevice implements CeeDeviceSource {
    private CompoundTag legacyState = new CompoundTag();
    protected NativeElectricalDevice(Level level, BlockPos pos, DevicesSavedData sd, SimulatedDeviceType<?> type) {
        super(level, pos, sd, type);
    }
    protected int nativePortCount() { return 2; }
    protected abstract Map<String, Double> nativeParameters();
    @Override public final DeviceInput electromagnetism$captureInput() {
        var id = ((CeeDeviceIdentity)this).electromagnetism$nativeIdentity();
        return new DeviceInput(id, type.id().toString(),
                IntStream.range(0, nativePortCount()).mapToObj(i -> new PortId(id, i)).toList(), nativeParameters());
    }
    @Override public final void preTick(BridgeCollector bridges) { throw NativeSimulation.legacyCall("preTick"); }
    @Override public final void postTick(SimulationResults results) { throw NativeSimulation.legacyCall("postTick"); }
    @Override public void read(CompoundTag tag) { legacyState = tag.copy(); }
    @Override public void write(CompoundTag tag) { tag.merge(legacyState.copy()); }
}
