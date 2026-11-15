// Disposable save only. A fresh End circuit isolates the winding startup from old overworld history.
var srv=Java.loadClass('net.neoforged.neoforge.server.ServerLifecycleHooks').getCurrentServer();
var level=srv.getLevel(Java.loadClass('net.minecraft.world.level.Level').END);var pos=Java.loadClass('net.minecraft.core.BlockPos');
var sd=Java.loadClass('com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData').load(level);
level.setChunkForced(2,0,true);var blocks=Java.loadClass('net.minecraft.core.registries.BuiltInRegistries').BLOCK;var rl=Java.loadClass('net.minecraft.resources.ResourceLocation');
var keys=[];blocks.keySet().forEach(function(k){var s=String(k);if(s.indexOf('electroenergetics:')===0 && (s.indexOf('rotor')>=0||s.indexOf('stator')>=0))keys.push(s);});
JSON.stringify({keys:keys,status:String(sd.ticker.electromagnetism$nativeStatus()),progress:String(sd.ticker.electromagnetism$nativeProgress())});
