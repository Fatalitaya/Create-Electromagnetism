package com.electromagnetism.content.instruments;
import com.electromagnetism.integration.cee.nativebridge.NativeElectricalDevice;
import com.george_vi.electroenergetics.devices.device.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import java.util.Map;

/** Passive port parameters. Dynamic current/voltage remain opaque legacy save data. */
public final class AntennaDevice extends NativeElectricalDevice {
    public double radiationResistance = 50, couplingCapacitance = 1e-6;
    public AntennaDevice(Level level, BlockPos pos, DevicesSavedData sd, SimulatedDeviceType<?> type) { super(level,pos,sd,type); }
    @Override protected Map<String, Double> nativeParameters() {
        return Map.of("radiationResistanceOhms", radiationResistance, "couplingCapacitanceFarads", couplingCapacitance);
    }
    @Override public void read(CompoundTag tag) {
        super.read(tag);
        radiationResistance = tag.contains("Resistance") ? tag.getDouble("Resistance") : 50;
        couplingCapacitance = tag.contains("Capacitance") ? tag.getDouble("Capacitance") : 1e-6;
    }
    @Override public void write(CompoundTag tag) { super.write(tag); tag.putDouble("Resistance",radiationResistance); tag.putDouble("Capacitance",couplingCapacitance); }
    @Override public boolean shouldRemove(BlockState oldState, BlockState next) { return !(next.getBlock() instanceof AntennaBlock); }
}
