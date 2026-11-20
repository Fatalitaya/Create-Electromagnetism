"""Run the creative plant benchmark against the local AutoJS server."""
import argparse
import json
import sys
import time
from functools import partial
from pathlib import Path
from urllib.error import URLError
from urllib.request import Request, urlopen


SCENE = r"""
// Server AutoJS. Actions: layout, setup, connect, start, signal-high, signal-low, observe, benchmark, stop.
// Origin is the northwest corner at device height in the overworld. Setup requires empty,
// loaded space including the support layer. Leave a game tick between setup and connect.
// Terminal lists describe physical copper runs; consecutive entries get one wire each.
(function () {
    var action = typeof ceeSceneAction === 'undefined' ? 'observe' : String(ceeSceneAction);
    var scenario = typeof ceeScenario === 'undefined' ? 'plant' : String(ceeScenario);
    var shorted = scenario === 'half-wave-short', rectifier = shorted || scenario === 'full-wave';
    if (!rectifier && scenario !== 'plant') throw new Error('Unknown scenario '+scenario);
    var origin = typeof ceeSceneOrigin === 'undefined' ? [shorted ? 144 : rectifier ? 160 : 112, -59, 8] : ceeSceneOrigin;
    if (origin.length !== 3 || origin.some(function(v){return typeof v !== 'number' || !isFinite(v) || v !== Math.floor(v);}))
        throw new Error('Origin requires three integer block coordinates');
    var items = [], byName = {}, wires = [], nets = {}, assigned = {};
    var em = 'electromagnetism:', cee = 'electroenergetics:';
    function part(name, kind, x, z, pins, props, y) {
        if (byName[name]) throw new Error('Duplicate device ' + name);
        var item = {name:name, kind:kind.indexOf(':') < 0 ? cee + kind : kind,
            x:x, y:y || 0, z:z, pins:pins, props:props || {}};
        items.push(item); byName[name] = item;
        return item;
    }
    function ballast(name, x, z, ohms) {
        part(name, 'creative_resistor', x, z, 2).ohms = ohms;
    }
    function net(name, list) {
        var ends = list.split(' ');
        nets[name] = ends;
        ends.forEach(function (end, i) {
            var fields = end.split('.'), item = byName[fields[0]], pin = Number(fields[1]);
            if (!item || fields.length !== 2 || pin < 0 || pin >= item.pins || pin !== Math.floor(pin) || assigned[end])
                throw new Error('Invalid or repeated terminal ' + end + ' in ' + name);
            assigned[end] = name;
            if (i) {
                if (ends[i - 1].split('.')[0] === fields[0]) throw new Error('Same-block wire in '+name);
                wires.push([ends[i - 1], end]);
            }
        });
    }

    function plantLayout() {
    // Three-phase alternator. Both stators face the rotor, on opposite faces.
    part('drive','create:creative_motor',1,3,0,{facing:'east'});
    part('rotor','alternator_rotor',2,3,0,{axis:'x'});
    part('magnet','stator',2,4,0,{facing:'north',roll:'true'});
    part('field',em+'excitation_stator',2,2,2,{facing:'south',roll:'true'});
    part('brush','three_phase_alternator_brushes',3,3,4,{facing:'east'});
    part('exciter','creative_battery',1,0,2,{ac:'false'});
    part('frequency','frequency_meter',5,3,2,{facing:'north'});
    part('sync','synchroscope',7,3,6,{facing:'north'});
    part('transformer','transformer',9,3,4);
    part('coil',em+'heating_coil',5,6,2);
    part('inductor','inductor',7,6,2);
    part('lamp','bulb',9,6,2);
    net('phase A','brush.1 frequency.0 sync.0 transformer.0 sync.3');
    net('phase B','brush.2 sync.1 coil.0 sync.4');
    net('phase C','brush.3 sync.2 inductor.0 sync.5');
    net('AC neutral','brush.0 coil.1 lamp.1 frequency.1 transformer.1');
    net('phase C lighting','inductor.1 lamp.0');

    // Isolated transformer secondary, mixed rectifier bridge and LC filter.
    part('d1',em+'high_voltage_rectifier',11,2,2);
    part('d2',em+'high_voltage_rectifier',13,2,2);
    part('d3','diode',11,5,2); part('d4','diode',13,5,2);
    part('choke',em+'power_choke',15,2,2);
    part('capacitor','capacitor',17,2,2);
    part('scope',em+'oscilloscope',17,5,2);
    part('meter','energy_meter',19,2,4);
    part('ammeter','ammeter',21,2,2);
    part('fuse','fuse',23,2,2);
    net('secondary A','transformer.2 d1.1 d3.0');
    net('secondary B','transformer.3 d2.1 d4.0');
    net('rectified positive','d1.0 d2.0 choke.0');
    net('filtered positive','choke.1 capacitor.0 scope.0 meter.0');
    net('rectified return','d3.1 d4.1 capacitor.1 scope.1 meter.1');
    net('meter output','meter.2 ammeter.0');
    net('fused feeder','ammeter.1 fuse.0');

    // 60 V auxiliary supply drives the field and a separately ballasted 56 V reference.
    // At 60 V and 220 ohms the Zener dissipates about 1 W, below its 5 W rating.
    ballast('referenceBallast',4,0,220);
    part('zener',em+'zener_diode',7,0,2); part('referenceMeter','voltmeter',9,0,2);
    net('excitation positive','exciter.0 field.0 referenceBallast.0');
    net('clamped reference','referenceBallast.1 zener.0 referenceMeter.0');
    net('excitation return','exciter.1 field.1 zener.1 referenceMeter.1');

    // The control divider drives complementary low/high-side switches. Both coil and
    // contact of the relay belong to the same DC distribution system. A flyback diode
    // spans the relay coil. Separate ballasts limit motor, battery and lamp currents.
    part('divider','redstone_potentiometer',22,6,3);
    part('nmos',em+'n_mosfet',19,8,3); part('pmos',em+'p_mosfet',25,8,3);
    part('relay','relay',17,8,4); part('flyback','diode',15,8,2);
    part('buzzer','buzzer',25,11,2);
    ballast('motorBallast',15,11,120); part('motor','white_electric_motor',13,11,2);
    ballast('chargeBallast',21,11,220); part('cell','accumulator',23,11,2);
    ballast('baseBallast',19,11,4700);
    part('npn',em+'npn_transistor',17,14,3);
    part('indicator','indicator_bulb',15,14,2,{side:'0'});
    ballast('pnpBase',23,14,4700); part('pnp',em+'pnp_transistor',25,14,3);
    ballast('bellBallast',23,17,120); part('bell',em+'electric_bell',21,17,2);
    ballast('lampBallast',15,17,120); part('fixture',em+'light_fixture',17,17,2);
    part('dcMeter','voltmeter',19,14,2);
    net('DC distribution','fuse.1 divider.0 pmos.2 pnp.2 chargeBallast.0 dcMeter.0 indicator.0 relay.2 flyback.0 relay.0');
    net('DC return','meter.3 nmos.2 motor.1 fixture.1 npn.2 bell.1 dcMeter.1 cell.1 buzzer.1 divider.2');
    net('wiper','divider.1 pmos.0 pnpBase.0 baseBallast.0 nmos.0');
    net('relay driver','nmos.1 relay.1 flyback.1');
    net('motor and lighting contact','relay.3 motorBallast.0 lampBallast.0');
    net('motor load','motorBallast.1 motor.0'); net('lamp load','lampBallast.1 fixture.0');
    net('charge limiter','chargeBallast.1 cell.0');
    net('complementary buzzer','pmos.1 buzzer.0');
    net('indicator bias','baseBallast.1 npn.0'); net('indicator sink','npn.1 indicator.1');
    net('bell bias','pnpBase.1 pnp.0'); net('bell driver','pnp.1 bellBallast.0');
    net('bell load','bellBallast.1 bell.0');
    }

    var faultWire = ['lowC.1','brush.0'];
    function rectifierLayout() {
        part('drive','create:creative_motor',2,0,0,{facing:'south'},1);
        part('rotor','alternator_rotor',2,1,0,{axis:'z'},1);
        part('brush','three_phase_alternator_brushes',2,2,4,{facing:'south'},1);
        part('west','stator',1,1,0,{facing:'east',roll:'true'},1);
        part('east','stator',3,1,0,{facing:'west',roll:'true'},1);
        part('bottom','stator',2,1,0,{facing:'up',roll:'false'});
        part('top','stator',2,1,0,{facing:'down',roll:'false'},2);
        var phases=['A','B','C'];
        if (!shorted) ballast('load',2,10,1000);
        phases.forEach(function(phase,index) {
            var tx='tx'+phase, low='low'+phase, high='high'+phase;
            part(tx,'transformer',1+index,3,4,{facing:'south'});
            var kind=shorted && phase!=='B' ? 'diode' : em+'high_voltage_rectifier';
            part(low,kind,1+index,phase==='B' ? 6 : 7,2,{
                facing:'up',roll:phase==='B' ? 'false' : 'true',flip:phase==='C' ? 'true' : 'false'
            });
            if (!shorted) part(high,kind,1+index,9,2,{facing:'up'});
            net('primary '+phase,'brush.'+(index+1)+' '+tx+'.1');
            net('secondary '+phase,tx+'.2 '+low+'.0'+(shorted ? '' : ' '+high+'.1'));
        });
        // Keep the six individual neutral wires and the triangle of anode wires
        // from the captured fault. Chain wiring here would change copper impedance.
        var neutral=['brush.0','txA.0','txB.0','txC.0','txA.3','txB.3','txC.3'];
        nets.neutral=neutral;
        neutral.forEach(function(end,i){assigned[end]='neutral';if(i)wires.push([neutral[0],end]);});
        net('negative','lowA.1 lowB.1 lowC.1'+(shorted ? '' : ' load.1'));
        if (shorted) wires.push(['lowA.1','lowC.1']);
        else net('positive','highA.0 highB.0 highC.0 load.0');
    }
    if (rectifier) rectifierLayout(); else plantLayout();

    var width = rectifier ? 5 : 27, depth = rectifier ? 12 : 19, control = [22,0,5];
    var kinds = {}, occupied = {}, supports = {};
    function key(x,y,z) { return [x,y,z].join(','); }
    items.forEach(function (i) {
        var k = key(i.x,i.y,i.z);
        if (occupied[k] || i.x<0 || i.x>=width || i.z<0 || i.z>=depth-1) throw new Error('Layout collision: '+k);
        occupied[k] = i.name; kinds[i.kind] = true;
        for (var pin=0;pin<i.pins;pin++) if (!assigned[i.name+'.'+pin]) throw new Error('Unwired '+i.name+'.'+pin);
    });
    items.forEach(function (i) {
        var k = key(i.x,i.y,i.z+1);
        if (i.pins && !occupied[k] && k!==control.join(',')) supports[k]=[i.x,i.y,i.z+1];
    });
    var summary = {action:action,scenario:scenario,origin:origin,dimensions:[width,rectifier ? 4 : 3,depth],blocks:items.length,
        blockTypes:Object.keys(kinds).length,wires:wires.length};
    if (shorted) summary.liveFaultWire=faultWire;
    if (action==='layout') {summary.devices=items; summary.nets=nets; summary.connections=wires; return JSON.stringify(summary);}
    if (['inspect','setup','connect','observe','benchmark','start','stop','signal-high','signal-low'].indexOf(action)<0 && !(shorted && action==='fault'))
        throw new Error('Unknown action '+action);
    if (rectifier && action.indexOf('signal-')===0) throw new Error('No control signal on '+scenario);
    var Pos=Java.loadClass('net.minecraft.core.BlockPos'), Registry=Java.loadClass('net.minecraft.core.registries.BuiltInRegistries');
    var Id=Java.loadClass('net.minecraft.resources.ResourceLocation'), Stack=Java.loadClass('net.minecraft.world.item.ItemStack');
    var Node=Java.loadClass('com.george_vi.electroenergetics.foundation.nodes.InWorldNode');
    var Wire=Java.loadClass('com.george_vi.electroenergetics.simulation.infrastructure.WireData');
    var Types=Java.loadClass('com.george_vi.electroenergetics.CEEWireTypes');
    var Scroll=Java.loadClass('com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour');
    var World=Java.loadClass('com.electromagnetism.integration.cee.nativebridge.CeeNativeWorld');
    var server=Java.loadClass('net.neoforged.neoforge.server.ServerLifecycleHooks').getCurrentServer();
    if (!server) throw new Error('Run this script on the server side');
    var level=server.overworld(), data=Java.loadClass('com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData').load(level);
    function pos(x,y,z){return new Pos(origin[0]+x,origin[1]+y,origin[2]+z);}
    function at(i){return pos(i.x,i.y,i.z);}
    function device(i){return data.deviceSD.getDevice(at(i));}
    function entity(i){return level.getBlockEntity(at(i));}
    function block(id){if(!Registry.BLOCK.containsKey(Id.parse(id)))throw new Error('Missing block '+id);return Registry.BLOCK.get(Id.parse(id));}
    function setting(i,value){var be=entity(i);be.getBehaviour(Scroll.TYPE).setValue(value);be.setChanged();be.sendData();}
    function voltage(v){var i=byName.exciter;setting(i,v*1000);device(i).voltage=v;device(i).acFrequency=0;device(i).phaseOffset=0;}
    function speed(v){var be=entity(byName.drive);be.generatedSpeed.setValue(v);be.setChanged();be.sendData();}
    var switchPos=pos(control[0],control[1],control[2]), red=block('minecraft:redstone_block');
    // Stop can still isolate either surviving source after removal/damage elsewhere.
    if(action==='stop'){
        summary.stopped=[];summary.unavailable=[];
        (rectifier ? ['drive'] : ['drive','exciter']).forEach(function(name){
            var i=byName[name];
            if(!level.isLoaded(at(i)) || !level.getBlockState(at(i)).is(block(i.kind)) || !entity(i) || (i.pins && !device(i))) summary.unavailable.push(name);
            else {if(name==='drive')speed(0);else voltage(0);summary.stopped.push(name);}
        });
        if(shorted){
            var a=new Node(1,at(byName.lowC)), b=new Node(0,at(byName.brush));
            if(data.isConnected(a,b)){
                var Connection=Java.loadClass('com.george_vi.electroenergetics.foundation.nodes.InWorldNodeConnection');
                data.removeConnection(new Connection(a,b));
                if(data.isConnected(a,b))throw new Error('Could not open fault wire');
            }
        }
        return JSON.stringify(summary);
    }
    var bulbId=Id.parse(em+'lv_light_bulb');
    if (!Registry.ITEM.containsKey(bulbId)) throw new Error('Missing LV bulb');
    items.forEach(function(i){
        i.state=block(i.kind).defaultBlockState();
        Object.keys(i.props).forEach(function(k){
            var p=i.state.getBlock().getStateDefinition().getProperty(k);
            if(!p || !p.getValue(i.props[k]).isPresent())throw new Error(i.name+' invalid state '+k);
            i.state=i.state.setValue(p,p.getValue(i.props[k]).get());
        });
        if(rectifier && i.kind===cee+'stator'){
            var Stator=Java.loadClass('com.george_vi.electroenergetics.content.rotor.StatorBlock');
            if(String(Stator.getRotorAxis(i.state))!=='z')i.state=i.state.cycle(Stator.ROLL);
        }
    });
    var x,y,z,empty=true;
    for(x=0;x<width;x++)for(z=0;z<depth;z++)for(y=-1;y<=(rectifier ? 2 : 1);y++){
        var here=pos(x,y,z);
        if(level.isOutsideBuildHeight(here) || !level.isLoaded(here))throw new Error('Outside loaded build area '+here);
        if(action==='setup' && !level.isEmptyBlock(here))throw new Error('Occupied footprint '+here);
        if(action==='inspect' && empty && !level.isEmptyBlock(here))empty=false;
    }
    if(action==='inspect')return JSON.stringify({empty:empty,tick:level.getGameTime()});
    if(action==='setup'){
        var floor=block('minecraft:smooth_stone').defaultBlockState();
        for(x=0;x<width;x++)for(z=0;z<depth;z++)level.setBlockAndUpdate(pos(x,-1,z),floor);
        Object.keys(supports).forEach(function(k){var a=supports[k];level.setBlockAndUpdate(pos(a[0],a[1],a[2]),floor);});
        var placer=Java.loadClass('net.neoforged.neoforge.common.util.FakePlayerFactory').getMinecraft(level);
        items.forEach(function(i){
            var p=at(i), b=i.state.getBlock();
            if(!level.setBlockAndUpdate(p,i.state))throw new Error('Placement failed '+i.name+' '+p);
            b.setPlacedBy(level,p,i.state,placer,new Stack(b.asItem()));
            if(i.pins && !device(i))throw new Error('Device registration failed '+i.name);
            if(i.name==='drive')speed(0);
            if(i.name==='exciter')voltage(0);
            if(i.ohms){entity(i).setResistance(i.ohms);entity(i).setChanged();entity(i).sendData();}
            if(i.name==='divider'){setting(i,1000000);device(i).resistance=1000;}
            if(i.kind===cee+'transformer'){setting(i,42);device(i).ratio=1;}
            if(i.name==='fixture')device(i).setInstalledBulb(Registry.ITEM.get(bulbId),null);
        });
        return JSON.stringify(summary);
    }
    var nodes={};
    items.forEach(function(i){
        var actual=level.getBlockState(at(i));
        if(!actual.is(i.state.getBlock()) || (i.pins && !device(i)))throw new Error('Missing or replaced '+i.name+' at '+at(i));
        Object.keys(i.props).forEach(function(k){
            var p=actual.getBlock().getStateDefinition().getProperty(k);
            if(String(actual.getValue(p))!==String(i.state.getValue(p)))throw new Error('Changed orientation: '+i.name+'/'+k);
        });
        for(var n=0;n<i.pins;n++){
            var node=new Node(n,at(i));
            if(!data.hasNode(node)) throw new Error('Terminal unavailable '+i.name+'.'+n+'; wait a tick after setup');
            nodes[i.name+'.'+n]=node;
        }
    });
    var missing=[], counts={};
    wires.forEach(function(w){
        if(data.isConnected(nodes[w[0]],nodes[w[1]])){w.forEach(function(e){counts[e]=(counts[e]||0)+1;});}
        else missing.push(w);
    });
    if(shorted){
        summary.faultClosed=data.isConnected(nodes[faultWire[0]],nodes[faultWire[1]]);
        if(summary.faultClosed)faultWire.forEach(function(e){counts[e]=(counts[e]||0)+1;});
    }
    Object.keys(nodes).forEach(function(e){
        if(data.getConnections(nodes[e]).size()!==(counts[e]||0))throw new Error('Unexpected external wire at '+e);
    });
    if(action==='connect'){
        if((!rectifier && device(byName.exciter).voltage!==0) || entity(byName.drive).getSpeed()!==0)throw new Error('Stop sources before wiring');
        missing.forEach(function(w){
            var a=nodes[w[0]],b=nodes[w[1]];
            data.connect(a,b,Wire.ofLength(Types.STANDARD.get(),Math.sqrt(a.sourcePos().distSqr(b.sourcePos()))+1));
            if(!data.isConnected(a,b))throw new Error('Wire refused '+w.join(' -> '));
        });
        summary.added=missing.length;return JSON.stringify(summary);
    }
    if(action!=='observe' && action!=='benchmark' && missing.length)throw new Error('Missing '+missing.length+' wires; run connect');
    if(action==='start'){if(!rectifier)voltage(60);speed(rectifier ? 256 : 128);}
    if(action==='fault' && !summary.faultClosed){
        if(entity(byName.rotor).getSpeed()!==256)throw new Error('Rotor must be at 256 rpm before closing the fault');
        var a=nodes[faultWire[0]],b=nodes[faultWire[1]];
        data.connect(a,b,Wire.ofLength(Types.STANDARD.get(),Math.sqrt(a.sourcePos().distSqr(b.sourcePos()))+1));
        summary.faultClosed=data.isConnected(a,b);
        if(!summary.faultClosed)throw new Error('Fault wire refused');
    }
    if(action==='signal-high' || action==='signal-low'){
        if(!level.isEmptyBlock(switchPos) && !level.getBlockState(switchPos).is(red))throw new Error('Control position occupied');
        if(action==='signal-high')level.setBlockAndUpdate(switchPos,red.defaultBlockState());
        else if(level.getBlockState(switchPos).is(red))level.removeBlock(switchPos,false);
    }
    function reading(name,role){
        var owner=device(byName[name]);
        var id=name==='meter' ? World.meterBranchId(owner,0) : World.branchId(owner,role);
        var v=data.ticker.electromagnetism$reading(id);
        if(!v.isPresent())return null;
        var r=v.get();
        return {from:r.windowStart(),to:r.windowEnd(),endV:r.endVoltage(),meanV:r.meanVoltage(),rmsV:r.rmsVoltage(),rmsA:r.rmsCurrent(),
            watts:r.activePower(),absorbedJ:r.totalAbsorbedEnergy(),
            kelvin:r.temperatureKelvin().isPresent()?r.temperatureKelvin().getAsDouble():null};
    }
    summary.missing=missing;summary.tick=level.getGameTime();
    summary.status=String(data.ticker.electromagnetism$nativeStatus());
    summary.blockers=String(data.ticker.electromagnetism$inspectInputs().blockers());
    summary.rpm=entity(byName.rotor).getSpeed();
    if(rectifier){
        summary.excitation=entity(byName.rotor).electromagnetism$permanentExcitation();
        summary.readings={};
        ['A','B','C'].forEach(function(p){
            summary.readings['tx'+p]=reading('tx'+p,'secondary-voltage');
            summary.readings['low'+p]=reading('low'+p,'junction');
            if(!shorted)summary.readings['high'+p]=reading('high'+p,'junction');
        });
        if(!shorted)summary.readings.load=reading('load','main');
        summary.domains=[];
        var report=data.ticker.electromagnetism$nativeProgress();
        if(report.isPresent()){
            summary.status=String(report.get().diagnostic().status());
            report.get().domains().forEach(function(d){
                summary.domains.push({session:String(d.session()),tick:d.tick(),completed:d.lastCompletedTick(),
                    skips:d.consecutiveSkips(),solveMs:d.lastSolveNanos()/1e6,status:String(d.diagnostic().status()),
                    reason:String(d.diagnostic().reason())});
            });
        }
        summary.sparkTps5s=Java.loadClass('me.lucko.spark.api.SparkProvider').get().tps().poll(
            Java.loadClass('me.lucko.spark.api.statistic.StatisticWindow$TicksPerSecond').SECONDS_5);
        return JSON.stringify(summary);
    }
    summary.excitationVolts=device(byName.exciter).voltage;
    summary.frequencyHz=entity(byName.frequency).electromagnetism$getFrequency();
    summary.phaseOrder=entity(byName.sync).electromagnetism$isValidConnection();
    summary.wiper=device(byName.divider).progress;
    summary.meterKwh=entity(byName.meter).totalEnergy;
    summary.readings={};
    items.forEach(function(i){
        if(!i.pins || i.name==='brush')return;
        var role=({exciter:'source/dc',transformer:'secondary-voltage',
            cell:'cell/0/copper',sync:'sync/0',zener:'junction',d1:'junction',d2:'junction',d3:'junction',
            d4:'junction',flyback:'junction'})[i.name] || 'main';
        if(i.name==='fixture')role='bulb/'+device(i).nativeBulbIdentity();
        summary.readings[i.name]=reading(i.name,role);
    });
    if(action==='benchmark'){
        var progress=data.ticker.electromagnetism$nativeProgress();
        var domains=[];
        if(progress.isPresent())progress.get().domains().forEach(function(d){
            domains.push({session:String(d.session()),tick:d.tick(),completed:d.lastCompletedTick(),attempts:d.attemptedTicks(),
                accepted:d.completedTicks(),solveMs:d.lastSolveNanos()/1e6,status:String(d.diagnostic().status()),
                reason:String(d.diagnostic().reason())});
        });
        var rotor=entity(byName.rotor), source=summary.readings.exciter;
        summary.rotorStressSU=rotor.calculateStressApplied()*Math.abs(rotor.getTheoreticalSpeed());
        summary.domains=domains;
        var fresh=source && [source,summary.readings.transformer,summary.readings.dcMeter].every(function(r){
            return r && r.to>r.from && Math.abs(r.to-source.to)<1e-9;
        }) && domains.some(function(d){
            return d.completed===d.tick && d.status==='READY' && d.tick<=summary.tick &&
                d.tick>=summary.tick-1 && Math.abs(source.to-(d.tick+1)*0.05)<1e-9;
        });
        summary.passed=!!(!missing.length && fresh && summary.rpm===128 && summary.excitationVolts===60 &&
            Math.abs(source.meanV-60)<1e-5 && summary.rotorStressSU>0);
    }
    return JSON.stringify(summary);
})();

"""


def run_scene(action, origin, port=25585, scenario="plant"):
    code = f"var ceeSceneAction = {json.dumps(action)};\n"
    code += f"var ceeScenario = {json.dumps(scenario)};\n"
    if origin is not None:
        code += f"var ceeSceneOrigin = {json.dumps(origin)};\n"
    request = Request(
        f"http://127.0.0.1:{port}/mcp",
        data=json.dumps({
            "jsonrpc": "2.0", "id": 1, "method": "tools/call",
            "params": {"name": "eval_js", "arguments": {
                "side": "server", "code": code + SCENE, "timeout_ms": 20000,
            }},
        }).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    deadline = time.monotonic() + 180
    while True:
        with urlopen(request, timeout=30) as response:
            rpc = json.load(response)
        if "error" in rpc:
            raise RuntimeError(rpc["error"])
        result = rpc["result"]
        evaluation = json.loads(result["content"][0]["text"])
        if not result.get("isError") and evaluation.get("ok"):
            return json.loads(evaluation["value"])
        reason = str(evaluation.get("error", evaluation))
        if (scenario != "plant" or action not in ("inspect", "observe", "benchmark") or
                "waiting for the server thread" not in reason or time.monotonic() >= deadline):
            raise RuntimeError(reason)
        print(f"Waiting for the server to finish its current tick ({action})...", flush=True)


def benchmark_rectifier(run, scenario, min_tps, max_solve_ms):
    shorted = scenario == "half-wave-short"
    result = {"scenario": scenario, "passed": False, "samples": [],
              "limits": {"minEffectiveTps": min_tps, "maxSolveMs": max_solve_ms}}
    # Only stop a drive after verifying this fixture's blocks and external wires.
    owns_drive = False
    try:
        if run("inspect")["empty"]:
            run("setup")
        deadline = time.monotonic() + 30
        while True:
            try:
                before = run("observe")
                break
            except RuntimeError as error:
                if "Terminal unavailable" not in str(error) or time.monotonic() >= deadline:
                    raise
                time.sleep(0.1)
        result["origin"] = before["origin"]
        owns_drive = True
        run("stop")
        if before["missing"]:
            deadline = time.monotonic() + 10
            while run("observe")["rpm"] != 0:
                if time.monotonic() >= deadline:
                    raise RuntimeError("Drive did not stop before wiring")
                time.sleep(0.05)
            run("connect")
        started = run("start")
        deadline = time.monotonic() + 15
        while True:
            ready = run("observe")
            if ready["tick"] > started["tick"] and ready["rpm"] == 256 and ready["excitation"] == 4:
                break
            if time.monotonic() >= deadline:
                raise RuntimeError("Four-stator alternator did not reach 256 rpm")
            time.sleep(0.05)

        start = time.monotonic()
        result["beforeLoad"] = ready
        first_tick = run("fault")["tick"] if shorted else ready["tick"]
        last_tick = first_tick
        conducting = set()
        legs = [side + phase for side in (["low"] if shorted else ["low", "high"]) for phase in "ABC"]
        while last_tick - first_tick < 100:
            scene = run("benchmark")
            elapsed = time.monotonic() - start
            if elapsed > 20:
                result["lastObservation"] = scene
                raise RuntimeError("Fewer than 100 game ticks completed within the wall-time budget")
            if scene["tick"] == last_tick:
                time.sleep(0.05)
                continue
            last_tick = scene["tick"]
            result["samples"].append(scene)
            if scene["missing"] or scene["rpm"] != 256 or (shorted and not scene["faultClosed"]):
                raise RuntimeError("Fixture wiring or drive changed during the measurement")
            if not scene["domains"] or scene["status"] != "READY" or any(
                d["status"] != "READY" or d["completed"] != d["tick"] or d["skips"]
                or d["solveMs"] > max_solve_ms for d in scene["domains"]
            ):
                raise RuntimeError("Simulation rejected, skipped or exceeded the solve-time limit")
            readings = scene["readings"]
            if any(r is None or r["to"] <= r["from"] or abs(r["to"] - last_tick * 0.05) > 0.051
                   for r in readings.values()) or len({r["to"] for r in readings.values()}) != 1:
                raise RuntimeError("Missing, stale or unsynchronized electrical readings")
            conducting.update(k for k in legs if readings[k]["rmsA"] > 0.01)
            if not shorted and last_tick - first_tick >= 5:
                # An unfiltered six-pulse bridge selects the highest and lowest
                # secondary phase at each instant, less two forward drops.
                phases = [readings["tx" + p]["endV"] for p in "ABC"]
                span = max(phases) - min(phases)
                load = readings["load"]
                if (span <= 10 or load["watts"] <= 0 or load["meanV"] <= 0
                        or abs(load["endV"] - span) > 4 + 0.05 * span):
                    raise RuntimeError("DC load did not follow the six-pulse line-to-line envelope")
            time.sleep(0.05)
        result["effectiveTps"] = (last_tick - first_tick) / elapsed
        result["conductingLegs"] = sorted(conducting)
        if result["effectiveTps"] < min_tps or conducting != set(legs):
            raise RuntimeError("Insufficient tick throughput or a rectifier leg never conducted")
        result["passed"] = True
    except (OSError, ValueError, KeyError, RuntimeError) as error:
        result["error"] = str(error)
    finally:
        if owns_drive:
            try:
                result["stop"] = run("stop")
                if "drive" not in result["stop"]["stopped"]:
                    result["passed"] = False
                    result["stopError"] = "Benchmark drive unavailable at shutdown"
            except (OSError, ValueError, KeyError, RuntimeError) as error:
                result["passed"] = False
                result["stopError"] = str(error)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--action", default="benchmark", choices=(
        "benchmark", "observe", "layout", "setup", "connect", "start", "stop",
        "signal-high", "signal-low", "fault",
    ))
    parser.add_argument("--origin", type=int, nargs=3, metavar=("X", "Y", "Z"))
    parser.add_argument("--port", type=int, default=25585, help="Local AutoJS port")
    parser.add_argument("--scenario", choices=("plant", "half-wave-short", "full-wave"), default="plant")
    parser.add_argument("--min-tps", type=float, default=15, help="Minimum measured throughput for rectifier scenarios")
    parser.add_argument("--max-solve-ms", type=float, default=250, help="Maximum sampled domain solve time for rectifier scenarios")
    parser.add_argument("--output", type=Path, help="JSON output path for a benchmark run")
    args = parser.parse_args()
    if not (0 < args.min_tps <= 20 and 0 < args.max_solve_ms < float("inf")):
        parser.error("Require 0 < min-tps <= 20 and a finite positive max-solve-ms")
    run = partial(run_scene, origin=args.origin, port=args.port, scenario=args.scenario)
    if args.action == "benchmark" and args.scenario != "plant":
        scene = benchmark_rectifier(run, args.scenario, args.min_tps, args.max_solve_ms)
    elif args.action == "benchmark":
        if run("inspect")["empty"]:
            run("setup")
        deadline = time.monotonic() + 30
        while True:
            try:
                before = run("benchmark")
                break
            except RuntimeError as error:
                if "Terminal unavailable" not in str(error) or time.monotonic() >= deadline:
                    raise
                time.sleep(0.2)
        if before["missing"]:
            run("connect")
        scene = before
        if before["rpm"] != 128 or before["excitationVolts"] != 60:
            started = run("start")
            deadline = time.monotonic() + 30
            while True:
                scene = run("benchmark")
                if scene["tick"] > started["tick"] + 1:
                    break
                if time.monotonic() >= deadline:
                    scene["passed"] = False
                    scene["startupWait"] = {"startedTick": started["tick"], "observedTick": scene["tick"]}
                    break
                time.sleep(0.2)
        elif before["missing"]:
            scene = run("benchmark")
    else:
        scene = run(args.action)
    if args.action != "benchmark":
        print(json.dumps(scene, indent=2))
        return 0

    filename = "benchmark.json" if args.scenario == "plant" else args.scenario + ".json"
    report = args.output or Path(__file__).resolve().parents[1] / "build/creative-plant-baseline" / filename
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(scene, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: scene[key] for key in (
        "scenario", "passed", "tick", "origin", "blocks", "wires", "rpm", "excitationVolts",
        "rotorStressSU", "status", "domains", "effectiveTps", "error", "stopError",
    ) if key in scene}, indent=2))
    print(f"Full report: {report}")
    return 0 if scene["passed"] else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, URLError, ValueError, KeyError, IndexError, RuntimeError) as error:
        sys.exit(f"AutoJS run failed: {error}")
