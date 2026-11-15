package com.electromagnetism.content.lighting.fixture;

import com.electromagnetism.content.lighting.bulb.ILightBulb;
import com.electromagnetism.config.ModConfigs;
import com.george_vi.electroenergetics.devices.device.DevicesSavedData;
import com.george_vi.electroenergetics.devices.device.SimulatedDeviceType;
import com.electromagnetism.integration.cee.nativebridge.NativeElectricalDevice;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class LightFixtureDevice extends NativeElectricalDevice implements com.electromagnetism.integration.cee.nativebridge.NativeLamp {

    @Override protected java.util.Map<String, Double> nativeParameters() {
        return com.electromagnetism.integration.cee.nativebridge.LampParameters.capture(getBulbItem());
    }

    public static final float AMBIENT_TEMPERATURE = 22f;

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
        setPowerLevel(Math.min(properties.maxPowerLevel(),brightness>=0.9?2:brightness>=0.2?1:0));
        syncToBlockEntity(false);
    }
    public void acceptNativeBurn(com.electromagnetism.simulation.SimulationProtocol.ProtectionEvent event) {
        nativeAvailable=true;temperature=(float)(event.temperatureKelvin()-273.15);burned=true;playEffect=true;
        setPowerLevel(0);spawnBurnParticles();syncToBlockEntity(true);
    }
    public void nativeUnavailable(){nativeAvailable=false;setPowerLevel(0);syncToBlockEntity(false);}


    @Nullable
    private DyeColor color;

    private boolean playEffect;

    public LightFixtureBlockEntity be;

    public LightFixtureDevice(Level level, BlockPos pos, DevicesSavedData deviceSD, SimulatedDeviceType<?> type) {
        super(level, pos, deviceSD, type);
    }

    public boolean hasBulb() {
        return bulbItem != null && bulbItem != Items.AIR && bulbItem instanceof ILightBulb;
    }

    public Item getBulbItem() {
        return bulbItem == null ? Items.AIR : bulbItem;
    }

    public float getTemperature() {
        return nativeAvailable ? temperature : Float.NaN;
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
        setPowerLevel(0);
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

    private void setPowerLevel(int powerLevel) {
        if (!level.isLoaded(pos))
            return;
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof LightFixtureBlock))
            return;
        if (state.getValue(LightFixtureBlock.POWER) != powerLevel)
            level.setBlockAndUpdate(pos, state.setValue(LightFixtureBlock.POWER, powerLevel));
    }

    private void spawnBurnParticles() {
        if (level instanceof ServerLevel serverLevel)
            serverLevel.sendParticles(ParticleTypes.FLASH, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 1, 0, 0, 0, 0);
    }

    private void syncToBlockEntity(boolean force) {
        if (!level.isLoaded(pos))
            return;
        if (be == null && level.getBlockEntity(pos) instanceof LightFixtureBlockEntity found)
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
        if(tag.hasUUID("BulbInstance"))bulbIdentity=tag.getUUID("BulbInstance");
        nativeAvailable=false;
        if (tag.contains("Bulb")) {
            bulbItem = BuiltInRegistries.ITEM.get(ResourceLocation.parse(tag.getString("Bulb")));
        } else {
            bulbItem = Items.AIR;
        }
        temperature = tag.contains("Temperature") ? tag.getFloat("Temperature") : AMBIENT_TEMPERATURE;
        burned = tag.getBoolean("Burned");
        if (tag.contains("Color"))
            color = DyeColor.byId(tag.getInt("Color"));
        else
            color = null;
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
        return !(newState.getBlock() instanceof LightFixtureBlock);
    }

    public CompoundTag writeClientTag() {
        CompoundTag tag = new CompoundTag();
        write(tag);
        if (playEffect)
            tag.putBoolean("Effect", true);
        return tag;
    }

    @SuppressWarnings("unused")
    public void readClientTag(CompoundTag tag, HolderLookup.Provider registries) {
        read(tag);
    }
}
