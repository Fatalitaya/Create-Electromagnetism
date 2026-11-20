package com.electromagnetism.content.lighting.bulb;

import com.tterrag.registrate.builders.ItemBuilder;
import com.tterrag.registrate.util.nullness.NonNullUnaryOperator;
import net.minecraft.world.item.Item;

/** Bind filament parameters at registration so bulbs of one type share the same ratings. */
public class LightBulbItem extends Item implements ILightBulb {
    private ThermalProperties thermalProperties;

    public LightBulbItem(Properties properties) {
        super(properties);
    }

    public void setThermalProperties(ThermalProperties thermalProperties) {
        this.thermalProperties = thermalProperties;
    }

    @Override
    public ThermalProperties thermalProperties() {
        return thermalProperties;
    }

    public static <I extends LightBulbItem, P> NonNullUnaryOperator<ItemBuilder<I, P>> setRated(float ratedPower, float ratedVoltage, float minResistance, float thermalMass, int maxPowerLevel, boolean dyeable) {
        ThermalProperties thermal = ILightBulb.fromRated(ratedPower, ratedVoltage, minResistance, thermalMass, maxPowerLevel, dyeable);
        return b -> b.onRegister(item -> item.setThermalProperties(thermal));
    }
}
