// Read-only runtime progress check; no reflection or player operations.
var syncServer = Java.loadClass('net.neoforged.neoforge.server.ServerLifecycleHooks').getCurrentServer();
var syncData = Java.loadClass('com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData').load(syncServer.overworld());
var syncProgress = syncData.ticker.electromagnetism$nativeProgress();
JSON.stringify({status: String(syncData.ticker.electromagnetism$nativeStatus()), progress: String(syncProgress), caughtUp: syncProgress.isPresent() && syncProgress.get().backlogSeconds() === 0, playerModified: false});
