package com.electromagnetism.integration.cee.nativebridge;
import com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
@EventBusSubscriber(modid="electromagnetism")
public final class NativeWorldLifecycle {
    private NativeWorldLifecycle() {}
    @SubscribeEvent public static void starting(net.neoforged.neoforge.event.server.ServerStartingEvent event) {
        try {
            com.electromagnetism.simulation.NativeLinearBridge.loadPackaged();
            com.electromagnetism.simulation.NativeLinearBridge.initializeCatalog();
        } catch (java.io.IOException | LinkageError | RuntimeException unavailable) {
            com.mojang.logging.LogUtils.getLogger().warn("Unable to initialize native model catalog", unavailable);
        }
    }
    @SubscribeEvent public static void stopping(ServerStoppingEvent event) {
        for(ServerLevel level:event.getServer().getAllLevels()) close(level);
    }
    @SubscribeEvent public static void unloading(LevelEvent.Unload event) {
        if(event.getLevel() instanceof ServerLevel level) close(level);
    }
    private static void close(ServerLevel level) {
        ((CeeSimulationAccess)InfrastructureSavedData.load(level).ticker).electromagnetism$closeNative();
    }
}
