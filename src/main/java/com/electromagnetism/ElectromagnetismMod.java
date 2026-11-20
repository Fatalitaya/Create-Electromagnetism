package com.electromagnetism;

import com.electromagnetism.client.model.ModPartialModels;
import com.electromagnetism.registry.ModBlockEntityTypes;
import com.electromagnetism.registry.ModBlocks;
import com.electromagnetism.registry.ModCreativeTabs;
import com.electromagnetism.registry.ModDeviceTypes;
import com.electromagnetism.registry.ModDisplaySources;
import com.electromagnetism.registry.ModFanProcessingTypes;
import com.electromagnetism.registry.ModItems;
import com.electromagnetism.registry.ModSounds;

import com.electromagnetism.client.ElectromagnetismClient;
import com.electromagnetism.config.ModConfigs;
import com.mojang.logging.LogUtils;
import com.simibubi.create.foundation.data.CreateRegistrate;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * Register mod content and CEE integration during NeoForge loading, and install client hooks on
 * the client.
 */
@Mod(ElectromagnetismMod.ID)
public class ElectromagnetismMod {
    public static final String ID = "electromagnetism";
    public static final Logger LOGGER = LogUtils.getLogger();
    public static CreateRegistrate REGISTRATE;

    public ElectromagnetismMod(IEventBus modEventBus, ModContainer modContainer) {
        REGISTRATE = CreateRegistrate.create(ID);
        REGISTRATE.registerEventListeners(modEventBus);

        modEventBus.addListener(com.electromagnetism.content.instruments.ScopeNetworking::register);
        modEventBus.addListener(com.electromagnetism.content.instruments.AudioNetworking::register);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent event)->com.electromagnetism.content.instruments.AudioNetworking.clear());
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                com.electromagnetism.integration.cee.nativebridge.NativeDiagnosticsCommands::register);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
            (net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) ->
                com.electromagnetism.content.instruments.ScopeNetworking.forget(event.getEntity().getUUID()));
        ModItems.register();
        ModBlocks.register();
        ModPartialModels.register();
        ModBlockEntityTypes.register();
        ModDeviceTypes.register(modEventBus);
        ModSounds.register(modEventBus);
        ModFanProcessingTypes.register(modEventBus);
        ModCreativeTabs.register(modEventBus);
        ModConfigs.register(modContainer);
        ModDisplaySources.register();

        // Bind display sources after common registration so the CEE block entity types are
        // available.
        modEventBus.addListener(ModDisplaySources::attachToCEEBlockEntities);

        if (FMLEnvironment.dist == Dist.CLIENT)
            ElectromagnetismClient.init();
    }

    public static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath(ID, path);
    }
}
