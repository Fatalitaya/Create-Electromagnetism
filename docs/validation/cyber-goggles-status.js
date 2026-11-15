var srv=Java.loadClass('net.neoforged.neoforge.server.ServerLifecycleHooks').getCurrentServer();
var level=srv.overworld();
var sd=Java.loadClass('com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData').load(level);
JSON.stringify({status:String(sd.ticker.electromagnetism$nativeStatus()),progress:String(sd.ticker.electromagnetism$nativeProgress()),blockers:String(sd.ticker.electromagnetism$inspectInputs().blockers())});

