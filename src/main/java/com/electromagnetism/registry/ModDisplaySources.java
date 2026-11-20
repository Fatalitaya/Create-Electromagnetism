package com.electromagnetism.registry;

import com.electromagnetism.ElectromagnetismMod;

import com.electromagnetism.integration.cee.display.FrequencyMeterDisplaySource;
import com.electromagnetism.integration.cee.display.SynchroscopeDisplaySource;
import com.george_vi.electroenergetics.CEEBlockEntityTypes;
import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.tterrag.registrate.util.entry.RegistryEntry;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

import static com.electromagnetism.ElectromagnetismMod.REGISTRATE;

/** Expose CEE frequency meter and synchroscope readings to Create display links. */
public class ModDisplaySources {
    public static final RegistryEntry<DisplaySource, FrequencyMeterDisplaySource> FREQUENCY_METER =
            REGISTRATE.displaySource("frequency_meter", FrequencyMeterDisplaySource::new).register();
    public static final RegistryEntry<DisplaySource, SynchroscopeDisplaySource> SYNCHROSCOPE =
            REGISTRATE.displaySource("synchroscope", SynchroscopeDisplaySource::new).register();

    public static void register() {
    }

    /**
     * After common registration, attach the display sources to existing CEE block entity types on
     * the main thread.
     */
    public static void attachToCEEBlockEntities(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            DisplaySource.BY_BLOCK_ENTITY.add(CEEBlockEntityTypes.FREQUENCY_METER.get(), FREQUENCY_METER.get());
            DisplaySource.BY_BLOCK_ENTITY.add(CEEBlockEntityTypes.SYNCHROSCOPE.get(), SYNCHROSCOPE.get());
        });
    }
}
