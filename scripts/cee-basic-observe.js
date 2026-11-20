// Rerun this observer after reconnecting AutoJS to read the same devices.
var basicServer=Java.loadClass('net.neoforged.neoforge.server.ServerLifecycleHooks').getCurrentServer();
var basicLevel=basicServer.overworld();
var BasicPos=Java.loadClass('net.minecraft.core.BlockPos');
var basicData=Java.loadClass('com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData').load(basicLevel);
var BasicWorld=Java.loadClass('com.electromagnetism.integration.cee.nativebridge.CeeNativeWorld');
var basicLayout=[];
[{z:10,name:'single'},{z:18,name:'series'},{z:26,name:'parallel'}].forEach(function(row){
    [{x:18,role:'source'},{x:22,role:'switch'},{x:26,role:'ammeter'},{x:38,role:'voltmeter'},{x:30,role:'load0'}].forEach(function(item){item.z=row.z;item.row=row.name;basicLayout.push(item);});
    if(row.name!=='single')basicLayout.push({x:34,z:row.z,row:row.name,role:'load1'});
});
var basicInspection=basicData.ticker.electromagnetism$inspectInputs();
JSON.stringify({status:String(basicData.ticker.electromagnetism$nativeStatus()),progress:String(basicData.ticker.electromagnetism$nativeProgress()),blockers:String(basicInspection.blockers()),devices:basicLayout.map(function(item){
    var at=new BasicPos(item.x,-59,item.z), device=basicData.deviceSD.getDevice(at);
    var optional=device?basicData.ticker.electromagnetism$reading(BasicWorld.branchId(device,'main')):null;
    var reading=optional&&optional.isPresent()?optional.get():null;
    return {row:item.row,role:item.role,position:String(at),exists:device!==null,reading:reading?{volts:reading.meanVoltage(),amps:reading.meanCurrent(),watts:reading.activePower(),windowEnd:reading.windowEnd()}:null};
})});
