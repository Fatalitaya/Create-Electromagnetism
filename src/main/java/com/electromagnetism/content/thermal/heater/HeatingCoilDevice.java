package com.electromagnetism.content.thermal.heater;
import com.electromagnetism.integration.cee.nativebridge.NativeElectricalDevice;
import com.george_vi.electroenergetics.devices.device.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import java.util.Map;

import com.electromagnetism.config.ModConfigs;

/** Configuration and world binding only; thermal state is preserved in opaque legacy NBT. */
public class HeatingCoilDevice extends NativeElectricalDevice {
    public static final float AMBIENT_TEMPERATURE = 22f, OVERHEAT_TEMPERATURE = 600f;
    public HeatingCoilBlockEntity be;
    private boolean nativeBurned;
    public boolean nativeBurned(){return nativeBurned;}
    private HeatingCoilBlockEntity binding() {
        if(level.isLoaded(pos) && level.getBlockEntity(pos) instanceof HeatingCoilBlockEntity found)be=found;
        return be!=null && !be.isRemoved()?be:null;
    }
    public void acceptNative(com.electromagnetism.simulation.SimulationProtocol.Reading reading) {
        var target=binding();if(target==null || reading.temperatureKelvin().isEmpty())return;
        target.setBurned(nativeBurned);target.setTemperature((float)(reading.temperatureKelvin().getAsDouble()-273.15));
    }
    public void acceptNativeBurn(com.electromagnetism.simulation.SimulationProtocol.ProtectionEvent event) {
        nativeBurned=true;var target=binding();if(target!=null){target.setBurned(true);target.nativeUnavailable();}
    }
    public void nativeUnavailable(){var target=binding();if(target!=null)target.nativeUnavailable();}
    @Override public void read(CompoundTag tag){super.read(tag);nativeBurned=tag.getBoolean("NativeBurned");}
    @Override public void write(CompoundTag tag){super.write(tag);tag.putBoolean("NativeBurned",nativeBurned);}

    public HeatingCoilDevice(Level level, BlockPos pos, DevicesSavedData sd, SimulatedDeviceType<?> type) { super(level,pos,sd,type); }
    @Override protected Map<String, Double> nativeParameters() {
        return Map.of("resistanceOhms", (double)ModConfigs.server().heatingCoilResistance.getF(),
                "maxPowerWatts", (double)ModConfigs.server().heatingCoilMaxPower.getF(),
                "heatCapacityJoulesPerKelvin", (double)ModConfigs.server().heatingCoilHeatCapacityJoulesPerKelvin.getF());
    }
    @Override public boolean shouldRemove(BlockState oldState, BlockState next) { return !(next.getBlock() instanceof HeatingCoilBlock); }
}
