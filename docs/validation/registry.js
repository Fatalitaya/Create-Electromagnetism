// Run with AutoJS eval_js, side=server, in the isolated validation world.
var Registries=Java.loadClass('com.george_vi.electroenergetics.CEERegistries');
var result={};
['SIMULATED_DEVICE_TYPE','PANEL_ATTACHMENT_TYPE','WIRE_TYPE'].forEach(function(name) {
    var keys=[]; Registries[name].keySet().forEach(function(key){keys.push(String(key));}); result[name]=keys.sort();
});
JSON.stringify(result);
