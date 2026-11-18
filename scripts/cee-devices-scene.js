// Server-side CEE scenarios. All coordinates and mutations are confined to this scene.
// Run setup, then connect on a subsequent tick so CEE can register terminals.
// Other actions: observe|open|close|discharge|charge.
var sceneAction=typeof ceeSceneAction==='undefined'?'observe':ceeSceneAction;
var ScenePos=Java.loadClass('net.minecraft.core.BlockPos');
var SceneRegistry=Java.loadClass('net.minecraft.core.registries.BuiltInRegistries');
var SceneId=Java.loadClass('net.minecraft.resources.ResourceLocation');
var SceneStack=Java.loadClass('net.minecraft.world.item.ItemStack');
var SceneNode=Java.loadClass('com.george_vi.electroenergetics.foundation.nodes.InWorldNode');
var SceneWire=Java.loadClass('com.george_vi.electroenergetics.simulation.infrastructure.WireData');
var SceneWireType=Java.loadClass('com.george_vi.electroenergetics.CEEWireTypes');
var SceneScroll=Java.loadClass('com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour');
var SceneWorld=Java.loadClass('com.electromagnetism.integration.cee.nativebridge.CeeNativeWorld');
var sceneServer=Java.loadClass('net.neoforged.neoforge.server.ServerLifecycleHooks').getCurrentServer();
if(!sceneServer)throw new Error('No active server');
var sceneLevel=sceneServer.overworld();
var sceneData=Java.loadClass('com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData').load(sceneLevel);
var sceneRows=[
    {name:'diode_forward',z:10,kind:'diode',input:1,output:0},
    {name:'diode_reverse',z:18,kind:'diode',input:0,output:1},
    {name:'capacitor',z:26,kind:'capacitor',input:0,output:1},
    {name:'inductor',z:34,kind:'inductor',input:0,output:1},
    {name:'relay',z:42,kind:'relay',input:2,output:3},
    {name:'double_switch',z:50,kind:'double_switch',input:0,output:2},
    {name:'fuse',z:58,kind:'fuse',input:0,output:1}
];
var sceneItems=[];
sceneRows.forEach(function(row){
    [{x:48,kind:'creative_battery',role:'source'}, {x:56,kind:row.kind,role:'dut'},
     {x:60,kind:'creative_resistor',role:'load'}, {x:64,kind:'ammeter',role:'ammeter'},
     {x:68,kind:'voltmeter',role:'voltmeter'}].forEach(function(item){item.z=row.z;item.row=row.name;sceneItems.push(item);});
    if(row.name==='relay')sceneItems.push({x:52,z:row.z,row:row.name,kind:'creative_battery',role:'coil_source'});
});
function scenePosition(x,z){return new ScenePos(x,-59,z);}
function sceneDevice(x,z){return sceneData.deviceSD.getDevice(scenePosition(x,z));}
function sceneBlock(kind){return SceneRegistry.BLOCK.get(SceneId.parse('electroenergetics:'+kind));}
function sceneVoltage(x,z,volts){
    var at=scenePosition(x,z),be=sceneLevel.getBlockEntity(at),device=sceneDevice(x,z);
    be.getBehaviour(SceneScroll.TYPE).setValue(volts*1000);
    device.voltage=volts;device.acFrequency=0;device.phaseOffset=0;
    be.setChanged();be.sendData();
}
var sceneLinks=[];
function sceneLink(row,x,pin,otherX,otherPin){
    var a=scenePosition(x,row.z),b=scenePosition(otherX,row.z);
    var wire=SceneWire.ofLength(SceneWireType.STANDARD.get(),Math.sqrt(a.distSqr(b))+1);
    sceneData.connect(new SceneNode(pin,a),new SceneNode(otherPin,b),wire);
    sceneLinks.push({row:row.name,a:[x,pin],b:[otherX,otherPin],ohms:wire.getResistance()});
}
// Check all expected registry entries and loaded chunks before any mutation.
sceneItems.forEach(function(item){
    if(!SceneRegistry.BLOCK.containsKey(SceneId.parse('electroenergetics:'+item.kind)))throw new Error('Missing registry entry '+item.kind);
    if(!sceneLevel.isLoaded(scenePosition(item.x,item.z)))throw new Error('Scene chunk is not loaded: '+item.x+','+item.z);
});
if(sceneAction==='setup'){
    // Refuse any occupied volume: reruns use observe, never replace an existing circuit.
    for(var sx=46;sx<=70;sx++)for(var sz=8;sz<=60;sz++)for(var sy=-60;sy<=-58;sy++){
        var check=new ScenePos(sx,sy,sz);
        if(!sceneLevel.isEmptyBlock(check))throw new Error('Occupied scene volume: '+check+'; use observe');
    }
    var floor=SceneRegistry.BLOCK.get(SceneId.parse('minecraft:smooth_stone')).defaultBlockState();
    for(var sx=46;sx<=70;sx++)for(var sz=8;sz<=60;sz++)sceneLevel.setBlockAndUpdate(new ScenePos(sx,-60,sz),floor);
    sceneItems.forEach(function(item){
        var at=scenePosition(item.x,item.z),block=sceneBlock(item.kind),state=block.defaultBlockState();
        sceneLevel.setBlockAndUpdate(at.south(),floor);sceneLevel.setBlockAndUpdate(at,state);
        block.setPlacedBy(sceneLevel,at,state,null,new SceneStack(block.asItem()));
        if(!sceneDevice(item.x,item.z))throw new Error('Device registration failed: '+at);
        if(item.role==='source'||item.role==='coil_source')sceneVoltage(item.x,item.z,12);
        if(item.role==='load'){
            var be=sceneLevel.getBlockEntity(at);be.setResistance(120);be.setChanged();be.sendData();
        }
    });
}else{
    // Validate the complete fixture before changing any source/contact.
    sceneItems.forEach(function(item){
        var at=scenePosition(item.x,item.z);
        if(!sceneLevel.getBlockState(at).is(sceneBlock(item.kind))||!sceneDevice(item.x,item.z))throw new Error('Fixture mismatch: '+at);
    });
    if(sceneAction==='connect')sceneRows.forEach(function(row){
        sceneLink(row,48,0,56,row.input);sceneLink(row,56,row.output,60,0);
        sceneLink(row,60,1,64,0);
        if(row.name==='double_switch'){
            sceneLink(row,64,1,56,3);sceneLink(row,56,1,48,1);
        }else sceneLink(row,64,1,48,1);
        sceneLink(row,60,0,68,0);sceneLink(row,60,1,68,1);
        if(row.name==='relay'){sceneLink(row,52,0,56,0);sceneLink(row,52,1,56,1);}
    });
    if(sceneAction==='open'||sceneAction==='close'){
        var closed=sceneAction==='close';
        var at=scenePosition(56,50);
        var property=Java.loadClass('com.george_vi.electroenergetics.content.cut_off_switch.CutOffSwitchBlock').CLOSED;
        sceneLevel.setBlockAndUpdate(at,sceneLevel.getBlockState(at).setValue(property,Java.loadClass('java.lang.Boolean').valueOf(closed)));
        sceneDevice(56,50).isClosed=closed;
        sceneVoltage(52,42,closed?12:0);
    }else if(sceneAction==='discharge'||sceneAction==='charge'){
        sceneVoltage(48,26,sceneAction==='charge'?12:0);
    }else if(sceneAction!=='observe'&&sceneAction!=='connect')throw new Error('Unknown action '+sceneAction);
}
if(sceneAction!=='observe'){sceneData.deviceSD.setDirty();sceneData.setDirty();}
var sceneProgress=sceneData.ticker.electromagnetism$nativeProgress();
JSON.stringify({action:sceneAction,dimension:'minecraft:overworld',deviceY:-59,
    status:String(sceneData.ticker.electromagnetism$nativeStatus()),
    progress:String(sceneProgress),blockers:String(sceneData.ticker.electromagnetism$inspectInputs().blockers()),
    linksCreated:sceneLinks,
    note:sceneAction==='observe'?'Accepted readings':'Readings may precede this mutation; run observe on a subsequent game tick',
    devices:sceneItems.map(function(item){
        var device=sceneDevice(item.x,item.z),role=item.role==='source'||item.role==='coil_source'?'source/dc':'main';
        var optional=sceneData.ticker.electromagnetism$reading(SceneWorld.branchId(device,role));
        var r=optional.isPresent()?optional.get():null;
        var tag=new (Java.loadClass('net.minecraft.nbt.CompoundTag'))();device.write(tag);
        return {row:item.row,role:item.role,kind:item.kind,x:item.x,z:item.z,settings:String(tag),
            reading:r?{volts:r.meanVoltage(),amps:r.meanCurrent(),watts:r.activePower(),windowStart:r.windowStart(),windowEnd:r.windowEnd()}:null};
    })});
