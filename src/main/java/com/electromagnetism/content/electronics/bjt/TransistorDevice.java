package com.electromagnetism.content.electronics.bjt;
import com.electromagnetism.integration.cee.nativebridge.NativeElectricalDevice;
import com.george_vi.electroenergetics.devices.device.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import java.util.Map;

import com.electromagnetism.core.model.ElectricalLimits;

/** Persisted user parameters; charge histories are retained without Java integration. */
public class TransistorDevice extends NativeElectricalDevice {
    public double beta = 100, vbeOn = 0.7, icMax = 1;
    public boolean pnp;
    public TransistorDevice(Level level, BlockPos pos, DevicesSavedData sd, SimulatedDeviceType<?> type) { super(level,pos,sd,type); }
    @Override protected int nativePortCount() { return 3; }
    @Override protected Map<String, Double> nativeParameters() { return Map.of("beta",beta,"vbeOnVolts",vbeOn,"icMaxAmps",icMax,"pnp",pnp?1.0:0.0); }
    @Override public void read(CompoundTag tag) {
        super.read(tag);
        beta = tag.contains("Beta") ? ElectricalLimits.BETA.sanitize(tag.getDouble("Beta")) : 100;
        vbeOn = tag.contains("VbeOn") ? ElectricalLimits.VBE.sanitize(tag.getDouble("VbeOn")) : 0.7;
        icMax = tag.contains("IcMax") ? ElectricalLimits.COLLECTOR_CURRENT.sanitize(tag.getDouble("IcMax")) : 1;
    }
    @Override public void write(CompoundTag tag) { super.write(tag); tag.putDouble("Beta",beta); tag.putDouble("VbeOn",vbeOn); tag.putDouble("IcMax",icMax); }
    @Override public boolean shouldRemove(BlockState oldState, BlockState next) { return !(next.getBlock() instanceof TransistorBlock block) || block.isPnp() != pnp; }
}
