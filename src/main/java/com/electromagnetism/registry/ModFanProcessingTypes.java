package com.electromagnetism.registry;

import com.electromagnetism.ElectromagnetismMod;

import com.electromagnetism.integration.create.processing.HeaterBlastingType;
import com.electromagnetism.integration.create.processing.HeaterSmokingType;
import com.simibubi.create.api.registry.CreateRegistries;
import com.simibubi.create.content.kinetics.fan.processing.FanProcessingType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Register fan processing types for the coil's smoking and blasting ranges. */
public class ModFanProcessingTypes {
    private static final DeferredRegister<FanProcessingType> TYPES =
            DeferredRegister.create(CreateRegistries.FAN_PROCESSING_TYPE, ElectromagnetismMod.ID);

    static {
        TYPES.register("heater_blasting", HeaterBlastingType::new);
        TYPES.register("heater_smoking", HeaterSmokingType::new);
    }

    public static void register(IEventBus bus) {
        TYPES.register(bus);
    }
}
