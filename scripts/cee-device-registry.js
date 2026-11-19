var registry=Java.loadClass('net.minecraft.core.registries.BuiltInRegistries');
var ElectricalDeviceBlock=Java.loadClass('com.george_vi.electroenergetics.foundation.device.ElectricalDeviceBlock');
var deviceBlocks=[];
registry.BLOCK.keySet().forEach(function(key){
    var name=String(key);
    if(name.indexOf('electroenergetics:')!==0 && name!=='create:creative_motor')return;
    var block=registry.BLOCK.get(key),electrical=block instanceof ElectricalDeviceBlock;
    if(electrical || /transformer|alternator|stator/.test(name) || name==='create:creative_motor')
        deviceBlocks.push({id:name,electrical:electrical,state:String(block.defaultBlockState())});
});
deviceBlocks.sort(function(a,b){return a.id.localeCompare(b.id);});
JSON.stringify(deviceBlocks);
