var srv=Java.loadClass('net.neoforged.neoforge.server.ServerLifecycleHooks').getCurrentServer();
var level=srv.getLevel(Java.loadClass('net.minecraft.world.level.Level').END);var pos=Java.loadClass('net.minecraft.core.BlockPos');
var sd=Java.loadClass('com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData').load(level);
JSON.stringify({status:String(sd.ticker.electromagnetism$nativeStatus()),progress:String(sd.ticker.electromagnetism$nativeProgress())});
