package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.content.lighting.bulb.ILightBulb;
import net.minecraft.world.item.Item;
import java.util.Map;

/** Copies registered ratings only; does not evaluate temperature-dependent resistance. */
public final class LampParameters {
    private LampParameters() {}
    public static Map<String, Double> capture(Item item) {
        if (!(item instanceof ILightBulb bulb) || bulb.thermalProperties() == null)
            return Map.of("installed", 0.0);
        var p = bulb.thermalProperties();
        return Map.of("installed", 1.0, "ratedVoltageVolts", (double)p.ratedVoltage(),
                "ratedPowerWatts", (double)p.ratedPower(), "coldResistanceOhms", (double)p.minResistance(),
                "hotResistanceOhms", (double)p.maxResistance(), "heatCapacityJoulesPerKelvin", (double)com.electromagnetism.config.ModConfigs.server().nativeLampHeatCapacityJoulesPerKelvin.getF());
    }
}
