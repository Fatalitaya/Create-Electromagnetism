package com.electromagnetism.content.lighting.factory;

import com.electromagnetism.content.lighting.bulb.ILightBulb;
import com.electromagnetism.config.ModConfigs;
import com.george_vi.electroenergetics.devices.device.DevicesSavedData;
import com.george_vi.electroenergetics.devices.device.SimulatedDeviceType;
import com.electromagnetism.integration.cee.nativebridge.NativeElectricalDevice;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class FactoryLightDevice extends NativeElectricalDevice implements com.electromagnetism.integration.cee.nativebridge.NativeLamp {

    @Override protected java.util.Map<String, Double> nativeParameters() {
        return com.electromagnetism.integration.cee.nativebridge.LampParameters.capture(getBulbItem());
    }

    public static final float AMBIENT_TEMPERATURE = 22f;

    private static final double SHARE_RESISTANCE = 0.001;

    @Nullable
    private Item bulbItem = Items.AIR;

    private float temperature = AMBIENT_TEMPERATURE;

    private boolean burned;
    private java.util.UUID bulbIdentity=java.util.UUID.randomUUID();
    private boolean nativeAvailable;
    public java.util.UUID nativeBulbIdentity(){return bulbIdentity;}
    public boolean nativeAvailable(){return nativeAvailable;}
    public void acceptNative(com.electromagnetism.simulation.SimulationProtocol.Reading reading) {
        if(!hasBulb() || burned || reading.temperatureKelvin().isEmpty())return;
        nativeAvailable=true;temperature=(float)(reading.temperatureKelvin().getAsDouble()-273.15);
        var properties=((ILightBulb)bulbItem).thermalProperties();
        double brightness=(temperature-AMBIENT_TEMPERATURE)/(properties.operatingTemperature()-AMBIENT_TEMPERATURE);
        syncToBlockEntity(false);
        setPowerLevel(1+Math.min(properties.maxPowerLevel(),brightness>=0.9?2:brightness>=0.2?1:0));
        syncToBlockEntity(false);
    }
    public void acceptNativeBurn(com.electromagnetism.simulation.SimulationProtocol.ProtectionEvent event) {
        nativeAvailable=true;temperature=(float)(event.temperatureKelvin()-273.15);burned=true;playEffect=true;
        setPowerLevel(hasBulb()?1:0);spawnBurnParticles();syncToBlockEntity(true);
    }
    public void nativeUnavailable(){nativeAvailable=false;setPowerLevel(hasBulb()?1:0);syncToBlockEntity(false);}




    @Nullable
    private DyeColor color;

    private boolean playEffect;

    public FactoryLightBlockEntity be;

    public FactoryLightDevice(Level level, BlockPos pos, DevicesSavedData deviceSD, SimulatedDeviceType<?> type) {
        super(level, pos, deviceSD, type);
    }

    public boolean hasBulb() {
        return bulbItem != null && bulbItem != Items.AIR && bulbItem instanceof ILightBulb;
    }

    public Item getBulbItem() {
        return bulbItem == null ? Items.AIR : bulbItem;
    }

    public float getTemperature() {
        return nativeAvailable?temperature:Float.NaN;
    }

    public boolean isBurned() {
        return burned;
    }

    @Nullable
    public DyeColor getColor() {
        return color;
    }

    public void setInstalledBulb(@Nullable Item item, @Nullable DyeColor color) {
        this.bulbIdentity=java.util.UUID.randomUUID();this.nativeAvailable=false;
        this.bulbItem = item == null ? Items.AIR : item;
        this.color = color;
        this.temperature = AMBIENT_TEMPERATURE;
        this.burned = false;
        this.playEffect = false;
        setPowerLevel(hasBulb() ? 1 : 0);
        syncToBlockEntity(true);
    }

    public boolean trySetColor(DyeColor color) {
        if (!hasBulb())
            return false;
        ILightBulb.ThermalProperties properties = ((ILightBulb) bulbItem).thermalProperties();
        if (properties == null || !properties.dyeable())
            return false;
        this.color = color;
        syncToBlockEntity(true);
        return true;
    }

    private BlockPos projectionTarget() {
        int range = ModConfigs.server().factoryLightProjectionRange.get();
        BlockPos last = pos;
        for (int i = 1; i < range; i++) {
            BlockPos below = pos.below(i);
            if (!level.isLoaded(below))
                break;
            BlockState state = level.getBlockState(below);
            if (state.getBlock() instanceof FactoryLightLightBlock)
                last = below;
            else if (!state.isAir())
                break;
            else
                last = below;
        }
        return last;
    }

    private void setPowerLevel(int powerLevel) {
        if (!level.isLoaded(pos))
            return;
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof FactoryLightBlock))
            return;
        if (state.getValue(FactoryLightBlock.POWER) != powerLevel)
            level.setBlockAndUpdate(pos, state.setValue(FactoryLightBlock.POWER, powerLevel));
        if (be != null && !be.isRemoved())
            be.projectDown(Math.max(powerLevel - 1, 0));
    }

    private void spawnBurnParticles() {
        if (level instanceof ServerLevel serverLevel)
            serverLevel.sendParticles(ParticleTypes.FLASH, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 1, 0, 0, 0, 0);
    }

    private void syncToBlockEntity(boolean force) {
        if (!level.isLoaded(pos))
            return;
        if (be == null && level.getBlockEntity(pos) instanceof FactoryLightBlockEntity found)
            be = found;
        if (be == null || be.isRemoved()) {
            be = null;
            return;
        }
        be.applyFromDevice(getBulbItem(), temperature, burned, color, playEffect, nativeAvailable, force);
        playEffect = false;
    }

    @Override
    public void read(CompoundTag tag) {
        super.read(tag);
        if(tag.hasUUID("BulbInstance"))bulbIdentity=tag.getUUID("BulbInstance");nativeAvailable=false;
        if (tag.contains("Bulb"))
            bulbItem = BuiltInRegistries.ITEM.get(ResourceLocation.parse(tag.getString("Bulb")));
        else
            bulbItem = Items.AIR;
        temperature = tag.contains("Temperature") ? tag.getFloat("Temperature") : AMBIENT_TEMPERATURE;
        burned = tag.getBoolean("Burned");
        color = tag.contains("Color") ? DyeColor.byId(tag.getInt("Color")) : null;
    }

    @Override
    public void write(CompoundTag tag) {
        super.write(tag);
        tag.putUUID("BulbInstance",bulbIdentity);
        if (hasBulb())
            tag.putString("Bulb", BuiltInRegistries.ITEM.getKey(bulbItem).toString());
        else tag.remove("Bulb");
        tag.putFloat("Temperature", temperature);
        tag.putBoolean("Burned", burned);
        if (color != null)
            tag.putInt("Color", color.getId());
        else tag.remove("Color");
    }

    @Override
    public boolean shouldRemove(BlockState oldState, BlockState newState) {
        return !(newState.getBlock() instanceof FactoryLightBlock);
    }

    @SuppressWarnings("unused")
    public void readClientTag(CompoundTag tag, HolderLookup.Provider registries) {
        read(tag);
    }
}
