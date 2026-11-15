// Disposable copy only: fresh circuit in the End to avoid the old overworld backlog.
var srv=Java.loadClass('net.neoforged.neoforge.server.ServerLifecycleHooks').getCurrentServer();
var level=srv.getLevel(Java.loadClass('net.minecraft.world.level.Level').END);
var pos=Java.loadClass('net.minecraft.core.BlockPos');var rl=Java.loadClass('net.minecraft.resources.ResourceLocation');var blocks=Java.loadClass('net.minecraft.core.registries.BuiltInRegistries').BLOCK;
level.setChunkForced(2,0,true);
level.setBlockAndUpdate(new pos(32,69,0),blocks.get(rl.parse('minecraft:stone')).defaultBlockState());
level.setBlockAndUpdate(new pos(36,69,0),blocks.get(rl.parse('minecraft:stone')).defaultBlockState());
level.setBlockAndUpdate(new pos(32,70,0),blocks.get(rl.parse('electroenergetics:creative_battery')).defaultBlockState());
level.setBlockAndUpdate(new pos(36,70,0),blocks.get(rl.parse('electroenergetics:voltmeter')).defaultBlockState());
var sd=Java.loadClass('com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData').load(level);
'fixture placed';
