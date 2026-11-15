// Isolated world only. Fixture occupies (32..36,-60..-59,0).
var srv=Java.loadClass('net.neoforged.neoforge.server.ServerLifecycleHooks').getCurrentServer();
var level=srv.overworld();
var pos=Java.loadClass('net.minecraft.core.BlockPos');
var blocks=Java.loadClass('net.minecraft.core.registries.BuiltInRegistries').BLOCK;
var rl=Java.loadClass('net.minecraft.resources.ResourceLocation');
var cfg=Java.loadClass('com.george_vi.electroenergetics.config.CEEConfigs').server();
cfg.componentDamage.set(false);
level.setChunkForced(2,0,true);
function put(x,y,id){return level.setBlockAndUpdate(new pos(x,y,0),blocks.get(rl.parse(id)).defaultBlockState());}
put(32,-60,'minecraft:stone');put(36,-60,'minecraft:stone');
put(32,-59,'electroenergetics:creative_battery');put(36,-59,'electroenergetics:voltmeter');
JSON.stringify({source:String(level.getBlockState(new pos(32,-59,0))),load:String(level.getBlockState(new pos(36,-59,0)))});
