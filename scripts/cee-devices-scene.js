// Run setup on the server and connect on a later tick. The loads fixture includes the wiper,
// energy meter and thermal loads.
var sceneAction=typeof ceeSceneAction==='undefined'?'observe':ceeSceneAction;
var sceneFixture=typeof ceeSceneFixture==='undefined'?'components':ceeSceneFixture;
if(sceneFixture!=='components'&&sceneFixture!=='loads')throw new Error('Unknown fixture '+sceneFixture);
var loadFixture=sceneFixture==='loads';
var sceneActions=['setup','connect','observe'].concat(loadFixture
    ?['start','stop','wiper-low','wiper-high','meter-reset']:['open','close','discharge','charge']);
if(sceneActions.indexOf(sceneAction)<0)throw new Error('Unsupported '+sceneFixture+' action: '+sceneAction);
var ScenePos=Java.loadClass('net.minecraft.core.BlockPos');
var SceneRegistry=Java.loadClass('net.minecraft.core.registries.BuiltInRegistries');
var SceneId=Java.loadClass('net.minecraft.resources.ResourceLocation');
var SceneStack=Java.loadClass('net.minecraft.world.item.ItemStack');
var SceneNode=Java.loadClass('com.george_vi.electroenergetics.foundation.nodes.InWorldNode');
var SceneWire=Java.loadClass('com.george_vi.electroenergetics.simulation.infrastructure.WireData');
var SceneWireType=Java.loadClass('com.george_vi.electroenergetics.CEEWireTypes');
var SceneScroll=Java.loadClass('com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour');
var SceneWorld=Java.loadClass('com.electromagnetism.integration.cee.nativebridge.CeeNativeWorld');
var SceneTag=Java.loadClass('net.minecraft.nbt.CompoundTag');
var sceneServer=Java.loadClass('net.neoforged.neoforge.server.ServerLifecycleHooks').getCurrentServer();
if(!sceneServer)throw new Error('No active server');
var sceneLevel=sceneServer.overworld();
var sceneData=Java.loadClass('com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData').load(sceneLevel);
var sceneRows=loadFixture?[
    {name:'redstone_divider',z:66,kind:'redstone_potentiometer',volts:12,
     expected:'Pin 1 is the wiper. With the source on, wiper-low gives about 12 V to return, wiper-high about 0 V.'},
    {name:'energy_meter',z:74,kind:'energy_meter',volts:12,
     expected:'About 1.2 W through a 120-ohm load. Energy increases while running and holds after stop; meter-reset starts a new counter.'},
    {name:'bulb',z:82,kind:'bulb',volts:120,
     expected:'With power on, the bulb warms and its light output increases; after stop it cools and dims.'},
    {name:'buzzer',z:90,kind:'buzzer',volts:12,
     expected:'Delivered buzzer voltage follows the accepted RMS voltage when powered and drops to zero after stop.'},
    {name:'heater',z:98,kind:'resistive_heater',volts:120,
     expected:'Positive power and rising temperature when powered; power drops promptly after stop and temperature decays.'}
]:[
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
    var items=[{x:48,kind:'creative_battery',role:'source'}, {x:56,kind:row.kind,role:'dut'}];
    if(!loadFixture||row.name==='energy_meter')items.push({x:60,kind:'creative_resistor',role:'load'});
    if(!loadFixture)items.push({x:64,kind:'ammeter',role:'ammeter'});
    if(!loadFixture||row.name==='redstone_divider')items.push({x:68,kind:'voltmeter',role:'voltmeter'});
    items.forEach(function(item){item.z=row.z;item.row=row.name;sceneItems.push(item);});
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
var sceneWires=[];
function sceneLink(row,x,pin,otherX,otherPin){
    var a=scenePosition(x,row.z),b=scenePosition(otherX,row.z);
    sceneWires.push({row:row.name,a:[x,pin],b:[otherX,otherPin],from:new SceneNode(pin,a),
        to:new SceneNode(otherPin,b),length:Math.sqrt(a.distSqr(b))+1});
}
sceneRows.forEach(function(row){
    if(loadFixture){
        sceneLink(row,48,0,56,0);
        if(row.name==='redstone_divider'){
            sceneLink(row,48,1,56,2);sceneLink(row,56,1,68,0);sceneLink(row,56,2,68,1);
        }else{
            sceneLink(row,48,1,56,1);
            if(row.name==='energy_meter'){
                sceneLink(row,56,2,60,0);sceneLink(row,56,3,60,1);
            }
        }
        return;
    }
    sceneLink(row,48,0,56,row.input);sceneLink(row,56,row.output,60,0);
    sceneLink(row,60,1,64,0);
    if(row.name==='double_switch'){
        sceneLink(row,64,1,56,3);sceneLink(row,56,1,48,1);
    }else sceneLink(row,64,1,48,1);
    sceneLink(row,60,0,68,0);sceneLink(row,60,1,68,1);
    if(row.name==='relay'){sceneLink(row,52,0,56,0);sceneLink(row,52,1,56,1);}
});
// Check every registry entry and loaded chunk before modifying the world.
sceneItems.forEach(function(item){
    if(!SceneRegistry.BLOCK.containsKey(SceneId.parse('electroenergetics:'+item.kind)))throw new Error('Missing registry entry '+item.kind);
    if(!sceneLevel.isLoaded(scenePosition(item.x,item.z)))throw new Error('Scene chunk is not loaded: '+item.x+','+item.z);
});
if(sceneAction==='setup'){
    // Use observe for an occupied site. Setup requires an empty volume.
    var firstZ=loadFixture?64:8,lastZ=loadFixture?100:60;
    for(var sx=46;sx<=70;sx++)for(var sz=firstZ;sz<=lastZ;sz++)for(var sy=-60;sy<=-58;sy++){
        var check=new ScenePos(sx,sy,sz);
        if(!sceneLevel.isLoaded(check))throw new Error('Unloaded scene volume: '+check);
        if(!sceneLevel.isEmptyBlock(check))throw new Error('Occupied scene volume: '+check+'; use observe');
    }
    var floor=SceneRegistry.BLOCK.get(SceneId.parse('minecraft:smooth_stone')).defaultBlockState();
    for(var sx=46;sx<=70;sx++)for(var sz=firstZ;sz<=lastZ;sz++)sceneLevel.setBlockAndUpdate(new ScenePos(sx,-60,sz),floor);
    sceneItems.forEach(function(item){
        var at=scenePosition(item.x,item.z),block=sceneBlock(item.kind),state=block.defaultBlockState();
        sceneLevel.setBlockAndUpdate(at.south(),floor);sceneLevel.setBlockAndUpdate(at,state);
        // CEE records the placer as the device owner during placement.
        var placer=item.kind==='energy_meter'
            ?Java.loadClass('net.neoforged.neoforge.common.util.FakePlayerFactory').getMinecraft(sceneLevel):null;
        block.setPlacedBy(sceneLevel,at,state,placer,new SceneStack(block.asItem()));
        if(!sceneDevice(item.x,item.z))throw new Error('Device registration failed: '+at);
        if(item.role==='source'||item.role==='coil_source')sceneVoltage(item.x,item.z,loadFixture?0:12);
        if(item.role==='load'){
            var be=sceneLevel.getBlockEntity(at);be.setResistance(120);be.setChanged();be.sendData();
        }
        if(item.kind==='redstone_potentiometer'){
            var be=sceneLevel.getBlockEntity(at);
            be.getBehaviour(SceneScroll.TYPE).setValue(1000000); // Resistance is supplied in milliohms.
            sceneDevice(item.x,item.z).resistance=1000;
            be.setChanged();be.sendData();
        }
    });
}else{
    // Validate the whole fixture before changing sources or contacts.
    sceneItems.forEach(function(item){
        var at=scenePosition(item.x,item.z);
        if(!sceneLevel.getBlockState(at).is(sceneBlock(item.kind))||!sceneDevice(item.x,item.z))throw new Error('Fixture mismatch: '+at);
    });
    if(sceneAction==='connect'){
        sceneWires.forEach(function(wire){
            [wire.from,wire.to].forEach(function(node){
                if(!sceneData.hasNode(node))throw new Error('Terminal not registered; wait a game tick: '+node);
                if(!sceneData.getConnections(node).isEmpty())throw new Error('Fixture already or partially connected: '+node);
            });
        });
        sceneWires.forEach(function(link){
            var wire=SceneWire.ofLength(SceneWireType.STANDARD.get(),link.length);
            sceneData.connect(link.from,link.to,wire);
            sceneLinks.push({row:link.row,a:link.a,b:link.b,ohms:wire.getResistance()});
        });
    }else if(sceneAction==='open'||sceneAction==='close'){
        var closed=sceneAction==='close';
        var at=scenePosition(56,50);
        var property=Java.loadClass('com.george_vi.electroenergetics.content.cut_off_switch.CutOffSwitchBlock').CLOSED;
        sceneLevel.setBlockAndUpdate(at,sceneLevel.getBlockState(at).setValue(property,Java.loadClass('java.lang.Boolean').valueOf(closed)));
        sceneDevice(56,50).isClosed=closed;
        sceneVoltage(52,42,closed?12:0);
    }else if(sceneAction==='discharge'||sceneAction==='charge'){
        sceneVoltage(48,26,sceneAction==='charge'?12:0);
    }else if(sceneAction==='start'||sceneAction==='stop'){
        if(sceneAction==='start'&&sceneWires.some(function(wire){return !sceneData.isConnected(wire.from,wire.to);}))
            throw new Error('Connect all fixture wires before starting');
        sceneRows.forEach(function(row){sceneVoltage(48,row.z,sceneAction==='start'?row.volts:0);});
    }else if(sceneAction==='wiper-low'||sceneAction==='wiper-high'){
        var at=scenePosition(55,sceneRows[0].z),power=SceneRegistry.BLOCK.get(SceneId.parse('minecraft:redstone_block'));
        if(!sceneLevel.isEmptyBlock(at)&&!sceneLevel.getBlockState(at).is(power))throw new Error('Unexpected wiper control block: '+at);
        var air=SceneRegistry.BLOCK.get(SceneId.parse('minecraft:air'));
        sceneLevel.setBlockAndUpdate(at,(sceneAction==='wiper-high'?power:air).defaultBlockState());
    }else if(sceneAction==='meter-reset'){
        sceneDevice(56,sceneRows[1].z).electromagnetism$resetCounter();
    }
}
if(sceneAction!=='observe'){sceneData.deviceSD.setDirty();sceneData.setDirty();}
function sceneReading(id){
    var optional=sceneData.ticker.electromagnetism$reading(id);
    if(!optional.isPresent())return null;
    var r=optional.get(),temperature=r.temperatureKelvin();
    return {volts:r.meanVoltage(),amps:r.meanCurrent(),watts:r.activePower(),
        rmsVolts:r.rmsVoltage(),rmsAmps:r.rmsCurrent(),
        absorbedJoules:r.totalAbsorbedEnergy(),returnedJoules:r.totalReturnedEnergy(),
        temperatureKelvin:temperature.isPresent()?temperature.getAsDouble():null,
        windowStart:r.windowStart(),windowEnd:r.windowEnd()};
}
var sceneProgress=sceneData.ticker.electromagnetism$nativeProgress();
JSON.stringify({action:sceneAction,fixture:sceneFixture,dimension:'minecraft:overworld',deviceY:-59,gameTime:sceneLevel.getGameTime(),
    status:String(sceneData.ticker.electromagnetism$nativeStatus()),
    progress:String(sceneProgress),blockers:String(sceneData.ticker.electromagnetism$inspectInputs().blockers()),
    linksCreated:sceneLinks,expectedLinks:sceneWires.length,
    wiring:sceneWires.map(function(wire){
        return {row:wire.row,a:wire.a,b:wire.b,connected:sceneData.isConnected(wire.from,wire.to)};
    }),
    scenarios:loadFixture?sceneRows:null,
    note:sceneAction==='observe'?'Accepted readings':'Readings may precede this mutation; run observe on a subsequent game tick',
    devices:sceneItems.map(function(item){
        var device=sceneDevice(item.x,item.z),role=item.role==='source'||item.role==='coil_source'?'source/dc':'main';
        var id=item.kind==='energy_meter'?SceneWorld.meterBranchId(device,0):SceneWorld.branchId(device,role);
        var tag=new SceneTag();device.write(tag);
        var at=scenePosition(item.x,item.z),be=sceneLevel.getBlockEntity(at),details=null;
        if(item.kind==='energy_meter'){
            details={closed:device.isClosed,counterId:String(device.electromagnetism$counterIdentity()),
                displayedWatts:be.activePower,displayedKwh:be.totalEnergy};
        }else if(item.kind==='redstone_potentiometer'){
            details={trackOhms:device.resistance,wiperPosition:device.progress,redstone:sceneLevel.getBestNeighborSignal(at),
                secondTrack:sceneReading(SceneWorld.branchId(device,'track/1'))};
        }else if(item.kind==='bulb'||item.kind==='buzzer'){
            var saved=be.saveWithoutMetadata(sceneLevel.registryAccess());
            details=item.kind==='bulb'?{lightFraction:saved.getFloat('Light')}:{deliveredVolts:saved.getDouble('Voltage')};
        }else if(item.kind==='resistive_heater'){
            details={heatFraction:be.heat,deliveredVolts:be.voltage};
        }
        return {row:item.row,role:item.role,kind:item.kind,x:item.x,z:item.z,settings:String(tag),
            state:String(sceneLevel.getBlockState(at)),details:details,reading:sceneReading(id)};
    })});
