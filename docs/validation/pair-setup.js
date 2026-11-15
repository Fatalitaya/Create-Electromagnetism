// Run after gauge fixture, with the server globals it defines.
sd.ticker.electromagnetism$saveNative();
source=sd.deviceSD.getDevice(new pos(32,-59,0));
cfg.wiresBreak.set(false); cfg.componentDamage.set(false); source.voltage=0;
var sf6=Java.loadClass('com.george_vi.electroenergetics.content.transmission_distribution.sf6_breaker.SF6BreakerBlock');
var core=Java.loadClass('com.george_vi.electroenergetics.content.transmission_distribution.transformer.TransformerCoreBlock');
var direction=Java.loadClass('net.minecraft.core.Direction');
var parser=Java.loadClass('net.minecraft.commands.arguments.blocks.BlockStateParser');
function state(id){return parser.parseForBlock(blocks.asLookup(),id,false).blockState();}
var breakerBlock=blocks.get(rl.parse('electroenergetics:sulfur_hexafluoride_breaker'));
var coreBlock=blocks.get(rl.parse('electroenergetics:transformer_core'));
put(40,-60,'minecraft:stone');put(44,-60,'minecraft:stone');put(45,-60,'minecraft:stone');
level.setBlock(new pos(40,-59,0),state('electroenergetics:sulfur_hexafluoride_breaker[base=true]'),2);
level.setBlock(new pos(40,-58,0),state('electroenergetics:sulfur_hexafluoride_breaker[base=false]'),2);
level.setBlock(new pos(44,-59,0),state('electroenergetics:transformer_core[facing=east]'),2);
level.setBlock(new pos(45,-59,0),state('electroenergetics:transformer_core[facing=west]'),2);
JSON.stringify({sf6:String(level.getBlockState(new pos(40,-59,0))),core:String(level.getBlockState(new pos(44,-59,0)))});
