var registry=Java.loadClass('net.minecraft.core.registries.BuiltInRegistries');
var deviceBlocks=[];
registry.BLOCK.keySet().forEach(function(key){
    var name=String(key);
    if((name.indexOf('electroenergetics:')===0 && /diode|capacitor|inductor|relay|switch|fuse|transformer|regulator|variac|alternator|stator|electric_motor/.test(name)) || name==='create:creative_motor')deviceBlocks.push({id:name,state:String(registry.BLOCK.get(key).defaultBlockState())});
});
JSON.stringify(deviceBlocks);
