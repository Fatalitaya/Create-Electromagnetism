// Run setup on the server, connect on a later tick, then observe. Machine and instrument fixtures
// occupy separate sites.
var machineAction = typeof ceeSceneAction === 'undefined' ? 'observe' : ceeSceneAction;
var circuitBench = typeof ceeSceneFixture !== 'undefined' && ceeSceneFixture === 'bench';
var machineActions = ['setup', 'connect', 'observe', 'start', 'start-three-phase', 'stop', 'input-low', 'input-high', 'input-normal', 'load-light', 'load-heavy', 'load-normal', 'transformer-unity', 'transformer-rated', 'verify-machines'];
if (circuitBench) machineActions = ['setup','connect','observe','start','stop','signal-high','signal-low','wiper-high','wiper-low','core-down','core-up','core-unity'];
if (machineActions.indexOf(machineAction) < 0) throw new Error('Unsupported machine action: ' + machineAction);
var MachinePos = Java.loadClass('net.minecraft.core.BlockPos');
var MachineRegistry = Java.loadClass('net.minecraft.core.registries.BuiltInRegistries');
var MachineId = Java.loadClass('net.minecraft.resources.ResourceLocation');
var MachineStack = Java.loadClass('net.minecraft.world.item.ItemStack');
var MachineNode = Java.loadClass('com.george_vi.electroenergetics.foundation.nodes.InWorldNode');
var MachineWire = Java.loadClass('com.george_vi.electroenergetics.simulation.infrastructure.WireData');
var MachineWireTypes = Java.loadClass('com.george_vi.electroenergetics.CEEWireTypes');
var MachineScroll = Java.loadClass('com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour');
var MachineWorld = Java.loadClass('com.electromagnetism.integration.cee.nativebridge.CeeNativeWorld');
var machineServer = Java.loadClass('net.neoforged.neoforge.server.ServerLifecycleHooks').getCurrentServer();
if (!machineServer) throw new Error('No active server');
var machineLevel = machineServer.overworld();
var machineData = Java.loadClass('com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData').load(machineLevel);
var machineRows = circuitBench ? [
    {name:'series_rc', z:66, expected:'20 Hz AC through capacitor and 120 ohms. Steady load RMS current is nonzero, mean is near zero.'},
    {name:'series_rl', z:70, expected:'20 Hz AC through inductor and 120 ohms. Compare winding and load RMS currents.'},
    {name:'half_wave', z:74, expected:'Positive half cycles pass from anode 1 to cathode 0. Load mean voltage is positive and below RMS.'},
    {name:'shaft_divider', z:78, expected:'12 V across pins 0/2. Wiper pin 1 approaches 12 V at progress 0 and 0 V at progress 1.'},
    {name:'core_pair', z:82, expected:'Adjacent east/west cores. Under 120 ohms expect secondary/primary RMS near 1 by default, 0.5 with core-down, 2 with core-up.'},
    {name:'relay_indicators', z:86, expected:'Low enables the second lamp, high the first. Stop extinguishes both.'}
] : [
    {name: 'transformer_down', z: 10, expected: '20 Hz AC; Np/Ns=2; secondary RMS approximately half primary, with winding/wire losses.'},
    {name: 'transformer_up', z: 18, expected: '20 Hz AC; Np/Ns=0.5; secondary RMS approximately twice primary, with winding/wire losses.'},
    {name: 'regulator', z: 26, expected: '12 V DC target; 11/12/14 V input should regulate near 12 V after settling; tap range 0.8..1.2.'},
    {name: 'generator_single_phase', z: 34, expected: 'Single-phase sine AC, permanent stator, 32 rpm drive; frequency 32/60 Hz; voltage changes polarity and decays after stop.'},
    {name: 'generator_three_phase', z: 42, expected: 'Three equal phase-neutral loads; frequency meter approximately 32/60 Hz. Synchroscope compares the same ABC bus on both sides: valid order and approximately zero degrees. This is a same-bus reference check, not independent-generator synchronization.'},
    {name: 'motor', z: 50, expected: '12 V DC through 120 ohm ballast; finite winding current and shaft response; current decays after stop.'},
    {name: 'bridge_rectifier', z: 58, expected: '20 Hz, 12 V peak AC; full-wave pulsating positive load voltage, with two diode drops; no smoothing capacitor.'}
];
var machineItems = [], machineWires = [];
function machinePos(item) { return new MachinePos(item.x, item.y, item.z); }
function machineDevice(item) { return machineData.deviceSD.getDevice(machinePos(item)); }
function machineEntity(item) { return machineLevel.getBlockEntity(machinePos(item)); }
function machineItem(row, role, x, dz, kind, properties, electrical) {
    var item = {row: row.name, role: role, x: x, y: -59, z: row.z + dz,
        kind: kind.indexOf(':') < 0 ? 'electroenergetics:' + kind : kind,
        properties: properties || {}, electrical: electrical !== false};
    machineItems.push(item);
    return item;
}
function machineLink(a, pinA, b, pinB) { machineWires.push({a: a, pinA: pinA, b: b, pinB: pinB}); }
machineRows.forEach(function(row) {
    var source, dut, load;
    if (circuitBench) {
        var alternating = ['series_rc','series_rl','half_wave','core_pair'].indexOf(row.name) >= 0;
        source = row.source = machineItem(row,'source',80,0,'creative_battery',{ac:String(alternating)});
        source.ac = alternating;
        if (row.name === 'shaft_divider') {
            dut = machineItem(row,'divider',88,0,'potentiometer');
            row.drive = machineItem(row,'drive',88,0,'create:creative_motor',{facing:'down'},false);
            row.drive.y = -58;
            var meter = machineItem(row,'meter',96,0,'voltmeter');
            machineLink(source,0,dut,0); machineLink(source,1,dut,2);
            machineLink(dut,1,meter,0); machineLink(dut,2,meter,1);
        } else if (row.name === 'relay_indicators') {
            var normal = machineItem(row,'normal',87,0,'redstone_relay',{inverted:'false'});
            var inverted = machineItem(row,'inverted',89,0,'redstone_relay',{inverted:'true'});
            var lamps = machineItem(row,'lamps',94,0,'indicator_bulb',{side:'2'});
            machineLink(source,0,normal,0); machineLink(normal,1,lamps,0); machineLink(lamps,1,source,1);
            machineLink(source,0,inverted,0); machineLink(inverted,1,lamps,2); machineLink(lamps,3,source,1);
        } else {
            load = machineItem(row,'load',96,0,'creative_resistor');
            if (row.name === 'core_pair') {
                var primary = machineItem(row,'primary',88,0,'transformer_core',{facing:'east'});
                var secondary = machineItem(row,'secondary',89,0,'transformer_core',{facing:'west'});
                machineLink(source,0,primary,0); machineLink(source,1,primary,1);
                machineLink(secondary,0,load,0); machineLink(load,1,secondary,1);
            } else {
                var kind = {series_rc:'capacitor',series_rl:'inductor',half_wave:'diode'}[row.name];
                dut = machineItem(row,'dut',88,0,kind);
                machineLink(source,0,dut,kind === 'diode' ? 1 : 0);
                machineLink(dut,kind === 'diode' ? 0 : 1,load,0); machineLink(load,1,source,1);
            }
        }
        return;
    }
    if (row.name.indexOf('generator_') === 0) {
        row.drive = machineItem(row, 'drive', 87, 0, 'create:creative_motor', {facing: 'east'}, false);
        row.rotor = machineItem(row, 'rotor', 88, 0, 'alternator_rotor', {axis: 'x'}, false);
        row.stator = machineItem(row, 'stator', 88, 1, 'stator', {facing: 'north'}, false);
        row.brush = machineItem(row, 'brush', 89, 0,
            row.name === 'generator_single_phase' ? 'alternator_brushes' : 'three_phase_alternator_brushes', {facing: 'east'});
        for (var phase = 1; phase <= (row.name === 'generator_single_phase' ? 1 : 3); phase++) {
            load = machineItem(row, 'load_' + phase, 96, (phase - 1) * 2, 'creative_resistor');
            machineLink(row.brush, phase, load, 0);
            machineLink(load, 1, row.brush, 0); // Terminal 0 is neutral. Terminals 1 through 3 carry the phases.
        }
        if (row.name === 'generator_three_phase') {
            row.frequency = machineItem(row, 'frequency', 92, 2, 'frequency_meter', {facing: 'north', waterlogged: 'false'});
            row.synchroscope = machineItem(row, 'synchroscope', 92, 4, 'synchroscope', {facing: 'north', waterlogged: 'false'});
            machineLink(row.brush, 1, row.frequency, 0);
            machineLink(row.brush, 0, row.frequency, 1);
            for (var phase = 0; phase < 3; phase++) {
                machineLink(row.brush, phase + 1, row.synchroscope, phase);
                machineLink(row.brush, phase + 1, row.synchroscope, phase + 3);
            }
        }
        return;
    }
    var ac = row.name.indexOf('transformer_') === 0 || row.name === 'bridge_rectifier';
    source = row.source = machineItem(row, 'source', 80, 0, 'creative_battery', {ac: String(ac)});
    source.ac = ac;
    load = row.load = machineItem(row, row.name === 'motor' ? 'ballast' : 'load', 96, 0, 'creative_resistor');
    if (row.name.indexOf('transformer_') === 0) {
        dut = row.dut = machineItem(row, 'dut', 88, 0, 'transformer');
        machineLink(source, 0, dut, 0); machineLink(source, 1, dut, 1);
        machineLink(dut, 2, load, 0); machineLink(load, 1, dut, 3);
    } else if (row.name === 'regulator') {
        dut = row.dut = machineItem(row, 'dut', 88, 0, 'voltage_regulator', {top: 'true', bottom: 'true', sliced: 'false'});
        machineLink(source, 0, dut, 0); machineLink(source, 1, dut, 3);
        machineLink(dut, 1, load, 0); machineLink(load, 1, dut, 2);
    } else if (row.name === 'motor') {
        dut = row.dut = machineItem(row, 'dut', 88, 0, 'white_electric_motor');
        machineLink(source, 0, load, 0); machineLink(load, 1, dut, 0); machineLink(dut, 1, source, 1);
    } else {
        // The CEE diode conducts from anode 1 to cathode 0.
        var d1 = machineItem(row, 'diode_1', 86, 0, 'diode');
        var d2 = machineItem(row, 'diode_2', 90, 0, 'diode');
        var d3 = machineItem(row, 'diode_3', 86, 3, 'diode');
        var d4 = machineItem(row, 'diode_4', 90, 3, 'diode');
        machineLink(source, 0, d1, 1); machineLink(source, 1, d2, 1);
        machineLink(d1, 0, load, 0); machineLink(d2, 0, load, 0);
        machineLink(load, 1, d3, 1); machineLink(load, 1, d4, 1);
        machineLink(d3, 0, source, 0); machineLink(d4, 0, source, 1);
    }
});
function machineState(item) {
    var state = MachineRegistry.BLOCK.get(MachineId.parse(item.kind)).defaultBlockState();
    Object.keys(item.properties).forEach(function(name) {
        var property = state.getBlock().getStateDefinition().getProperty(name);
        if (!property) throw new Error('Missing block property ' + item.kind + '/' + name);
        var value = property.getValue(item.properties[name]);
        if (!value.isPresent()) throw new Error('Invalid block property ' + item.kind + '/' + name);
        state = state.setValue(property, value.get());
    });
    if (item.role === 'stator') {
        var Stator = Java.loadClass('com.george_vi.electroenergetics.content.rotor.StatorBlock');
        if (String(Stator.getRotorAxis(state)) !== 'x') state = state.cycle(Stator.ROLL);
        if (String(Stator.getRotorAxis(state)) !== 'x') throw new Error('Stator must align with X rotor axis');
    }
    return state;
}
function machineNotify(item) { var be = machineEntity(item); be.setChanged(); be.sendData(); }
function machineVoltage(item, volts) {
    machineEntity(item).getBehaviour(MachineScroll.TYPE).setValue(volts * 1000);
    var device = machineDevice(item);
    device.voltage = volts; device.acFrequency = item.ac ? 20 : 0; device.phaseOffset = 0;
    machineNotify(item);
}
function machineLoad(item, ohms) { machineEntity(item).setResistance(ohms); machineNotify(item); }
function machineDrive(item, rpm) { machineEntity(item).generatedSpeed.setValue(rpm); machineNotify(item); }
// Include the support layer and rotor neighbours in the site check.
var firstZ = circuitBench ? 64 : 8, lastZ = circuitBench ? 88 : 63;
for (var mx = 78; mx <= 100; mx++) for (var mz = firstZ; mz <= lastZ; mz++) {
    for (var my = -60; my <= -57; my++) {
        var check = new MachinePos(mx, my, mz);
        if (!machineLevel.isLoaded(check)) throw new Error('Unloaded scene volume: ' + check);
        if (machineAction === 'setup' && !machineLevel.isEmptyBlock(check)) throw new Error('Occupied scene volume: ' + check + '; use observe');
    }
}
machineItems.forEach(function(item) {
    if (!MachineRegistry.BLOCK.containsKey(MachineId.parse(item.kind))) throw new Error('Missing registry entry: ' + item.kind);
    item.state = machineState(item); // Validate all target block states before placing the first block.
    if (machineAction !== 'setup') {
        var actual = machineLevel.getBlockState(machinePos(item));
        if (!actual.is(item.state.getBlock()) || !item.electrical && !machineEntity(item) && item.role !== 'stator') throw new Error('Fixture mismatch: ' + machinePos(item));
        Object.keys(item.properties).forEach(function(name) {
            var property = actual.getBlock().getStateDefinition().getProperty(name);
            if (String(actual.getValue(property)) !== String(item.state.getValue(property))) throw new Error('Fixture orientation/state mismatch: ' + machinePos(item) + '/' + name);
        });
        if (item.role === 'stator' && String(actual) !== String(item.state)) throw new Error('Stator alignment mismatch');
        if (item.electrical && !machineDevice(item)) throw new Error('Missing device: ' + machinePos(item));
    }
});
var machineLinksCreated = 0;
if (machineAction === 'setup') {
    var floor = MachineRegistry.BLOCK.get(MachineId.parse('minecraft:smooth_stone')).defaultBlockState();
    for (var mx = 78; mx <= 100; mx++) for (var mz = firstZ; mz <= lastZ; mz++) machineLevel.setBlockAndUpdate(new MachinePos(mx, -60, mz), floor);
    // Wall components use their default north-facing orientation.
    machineItems.forEach(function(item) {
        if (item.electrical) machineLevel.setBlockAndUpdate(machinePos(item).south(), floor);
    });
    machineItems.forEach(function(item) {
        var block = item.state.getBlock(), at = machinePos(item);
        machineLevel.setBlockAndUpdate(at, item.state);
        block.setPlacedBy(machineLevel, at, item.state, null, new MachineStack(block.asItem()));
        if (item.electrical && !machineDevice(item)) throw new Error('Device registration failed: ' + at);
        if (item.role === 'source') machineVoltage(item, circuitBench ? 0 : 12);
        if (item.role === 'divider') {
            machineEntity(item).getBehaviour(MachineScroll.TYPE).setValue(1000000);
            machineDevice(item).resistance = 1000;
            machineNotify(item);
        }
        if (item.role.indexOf('load') === 0 || item.role === 'ballast') machineLoad(item, 120);
        if (item.role === 'drive') machineDrive(item, 0);
    });
    machineRows.forEach(function(row) {
        if (row.name.indexOf('transformer_') === 0) {
            // Ratio table indices 42, 29 and 55 select 1:1, 2:1 and 1:2, respectively.
            machineEntity(row.dut).getBehaviour(MachineScroll.TYPE).setValue(row.name === 'transformer_down' ? 29 : 55);
            machineDevice(row.dut).ratio = row.name === 'transformer_down' ? 2 : 0.5;
            machineNotify(row.dut);
        } else if (row.name === 'regulator') {
            machineEntity(row.dut).getBehaviour(MachineScroll.TYPE).setValue(12000);
            var device = machineDevice(row.dut);
            device.targetVoltage = 12; device.top = true; device.bottom = true; device.sliced = 0;
            machineNotify(row.dut);
        }
    });
} else if (machineAction === 'connect') {
    // Check every endpoint before wiring. Stop on a partially wired fixture to avoid duplicate
    // connections.
    machineWires.forEach(function(wire) {
        [new MachineNode(wire.pinA, machinePos(wire.a)), new MachineNode(wire.pinB, machinePos(wire.b))].forEach(function(node) {
            if (!machineData.hasNode(node)) throw new Error('Terminal not registered; wait a game tick: ' + node);
            if (!machineData.getConnections(node).isEmpty()) throw new Error('Fixture already or partially connected: ' + node);
        });
    });
    machineWires.forEach(function(wire) {
        var a = machinePos(wire.a), b = machinePos(wire.b);
        machineData.connect(new MachineNode(wire.pinA, a), new MachineNode(wire.pinB, b),
            MachineWire.ofLength(MachineWireTypes.STANDARD.get(), Math.sqrt(a.distSqr(b)) + 1));
        machineLinksCreated++;
    });
} else if (machineAction === 'transformer-unity' || machineAction === 'transformer-rated') {
    var transformers = machineRows.filter(function(row) { return row.name.indexOf('transformer_') === 0; });
    transformers.forEach(function(row) {
        var input = machineReading(machineDevice(row.dut), 'primary-voltage');
        if (!input || !(input.rmsVolts > 1) || !(machineDevice(row.source).voltage > 0))
            throw new Error(row.name + ' needs an energized primary before switching turns');
    });
    transformers.forEach(function(row) {
        var unity = machineAction === 'transformer-unity', down = row.name === 'transformer_down';
        machineEntity(row.dut).getBehaviour(MachineScroll.TYPE).setValue(unity ? 42 : down ? 29 : 55);
        machineNotify(row.dut);
        if (machineDevice(row.dut).ratio !== (unity ? 1 : down ? 2 : 0.5))
            throw new Error(row.name + ' turns control did not apply the selected ratio');
    });
} else if (machineAction.indexOf('core-') === 0) {
    machineItems.forEach(function(item) {
        if (item.role !== 'primary' && item.role !== 'secondary') return;
        var doubled = item.role === 'primary' ? machineAction === 'core-down' : machineAction === 'core-up';
        machineEntity(item).getBehaviour(MachineScroll.TYPE).setValue(doubled ? 20 : 10);
        machineNotify(item);
    });
} else if (machineAction === 'signal-high' || machineAction === 'signal-low') {
    var control = new MachinePos(88,-59,86);
    var redstone = MachineRegistry.BLOCK.get(MachineId.parse('minecraft:redstone_block'));
    if (!machineLevel.isEmptyBlock(control) && !machineLevel.getBlockState(control).is(redstone))
        throw new Error('Unexpected relay control block at ' + control);
    var air = MachineRegistry.BLOCK.get(MachineId.parse('minecraft:air'));
    machineLevel.setBlockAndUpdate(control,(machineAction === 'signal-high' ? redstone : air).defaultBlockState());
} else if (machineAction === 'wiper-high' || machineAction === 'wiper-low') {
    machineRows.forEach(function(row) {
        // A downward-facing motor reverses the sign of configured speed on the Y shaft.
        if (row.drive) machineDrive(row.drive,machineAction === 'wiper-high' ? -128 : 128);
    });
} else if (machineAction === 'start-three-phase') {
    // Power the instrument fixture separately from the other machine rows.
    machineRows.forEach(function(row) {
        if (row.drive) machineDrive(row.drive, row.name === 'generator_three_phase' ? 32 : 0);
        if (row.source) machineVoltage(row.source, 0);
    });
} else if (machineAction === 'start' || machineAction === 'stop') {
    if (circuitBench && machineAction === 'start' && machineWires.some(function(wire) {
        return !machineData.isConnected(new MachineNode(wire.pinA,machinePos(wire.a)),new MachineNode(wire.pinB,machinePos(wire.b)));
    })) throw new Error('Complete bench wiring before start');
    machineRows.forEach(function(row) {
        if (row.drive) machineDrive(row.drive, machineAction === 'start' && !circuitBench ? 32 : 0);
        if (row.source) machineVoltage(row.source, machineAction === 'start' ? 12 : 0);
    });
} else if (machineAction.indexOf('input-') === 0) {
    machineVoltage(machineRows[2].source, machineAction === 'input-low' ? 11 : machineAction === 'input-high' ? 14 : 12);
} else if (machineAction.indexOf('load-') === 0) {
    // The 120 Ω ballast limits stall current.
    machineItems.forEach(function(item) {
        if (item.role.indexOf('load') === 0) machineLoad(item, machineAction === 'load-light' ? 240 : machineAction === 'load-heavy' ? 60 : 120);
    });
}
if (machineAction !== 'observe' && machineAction !== 'verify-machines') { machineData.deviceSD.setDirty(); machineData.setDirty(); }
function machineReading(device, role) {
    var optional = machineData.ticker.electromagnetism$reading(MachineWorld.branchId(device, role));
    if (!optional.isPresent()) return null;
    var r = optional.get();
    return {meanVolts: r.meanVoltage(), rmsVolts: r.rmsVoltage(), peakVolts: r.peakVoltage(),
        meanAmps: r.meanCurrent(), rmsAmps: r.rmsCurrent(), watts: r.activePower(),
        absorbedJoules: r.totalAbsorbedEnergy(), returnedJoules: r.totalReturnedEnergy(),
        frequencyHz: r.frequency().isPresent() ? r.frequency().getAsDouble() : null,
        torqueNewtonMetres: r.torqueNewtonMetres().isPresent() ? r.torqueNewtonMetres().getAsDouble() : null,
        temperatureKelvin: r.temperatureKelvin().isPresent() ? r.temperatureKelvin().getAsDouble() : null,
        windowStart: r.windowStart(), windowEnd: r.windowEnd()};
}
if (machineAction === 'verify-machines') {
    machineRows.forEach(function(row) {
        var regulator = row.name === 'regulator';
        if (!regulator && row.name.indexOf('transformer_') !== 0) return;
        var dut = machineDevice(row.dut);
        var input = machineReading(dut, regulator ? 'regulator/input-voltage' : 'primary-voltage');
        var output = machineReading(dut, regulator ? 'regulator/output-voltage' : 'secondary-voltage');
        var load = machineReading(machineDevice(row.load), 'main');
        if (!input || !output || !load || input.windowEnd !== output.windowEnd || input.windowEnd !== load.windowEnd ||
                Math.abs(input.windowEnd - machineLevel.getGameTime() * 0.05) > 0.051)
            throw new Error(row.name + ' has no current accepted measurement window');
        var expected = regulator ? dut.targetVoltage : input.rmsVolts / dut.ratio;
        if (!(input.rmsVolts > 1 && expected > 0 && load.watts > 0 &&
                Math.abs(output.rmsVolts / expected - 1) < 0.05 && Math.abs(load.rmsVolts / expected - 1) < 0.05))
            throw new Error(row.name + ' expected ' + expected + ' V after settling; input=' + input.rmsVolts +
                    ', output=' + output.rmsVolts + ', load=' + load.rmsVolts + ' V, ' + load.watts + ' W');
        if (regulator) {
            var bridge = machineReading(dut, 'regulator/return');
            if (!bridge || bridge.windowEnd !== load.windowEnd || !(bridge.rmsAmps > 0.0001))
                throw new Error('Powered regulator has no current through its N2-N3 return');
        }
    });
}
var machineProgress = machineData.ticker.electromagnetism$nativeProgress();
JSON.stringify({action: machineAction, fixture: circuitBench ? 'bench' : 'machines', dimension: 'minecraft:overworld', deviceY: -59,
    gameTime: machineLevel.getGameTime(), linksCreated: machineLinksCreated, expectedLinks: machineWires.length,
    status: String(machineData.ticker.electromagnetism$nativeStatus()), progress: String(machineProgress),
    caughtUp: machineProgress.isPresent() && !machineProgress.get().domains().isEmpty() &&
        machineProgress.get().domains().stream().allMatch(function(domain) {
            return domain.lastCompletedTick() === machineProgress.get().tick();
        }),
    blockers: String(machineData.ticker.electromagnetism$inspectInputs().blockers()),
    note: 'Observations are not a pass verdict. Compare later accepted windows after settling; null means unavailable, not zero.',
    scenarios: machineRows.map(function(row) { return {name: row.name, z: row.z, expected: row.expected}; }),
    devices: machineItems.map(function(item) {
        var device = item.electrical ? machineDevice(item) : null, be = machineEntity(item), readings = {};
        var roles = ['main'];
        if (item.kind === 'electroenergetics:transformer' || item.role === 'primary') roles = ['primary-voltage', 'secondary-voltage', 'primary-meter', 'secondary-meter', 'winding-heat/0', 'winding-heat/1'];
        if (item.role === 'secondary' || item.role === 'drive') roles = [];
        if (item.role === 'lamps') roles = ['main','indicator/1'];
        if (item.role === 'divider') roles = ['main','track/1'];
        if (item.kind === 'electroenergetics:voltage_regulator') roles = ['regulator/input-voltage', 'regulator/output-voltage', 'regulator/input-power', 'regulator/output-copper'];
        if (item.role === 'brush') roles = []; // Look up source branches by rotor assembly UUID.
        if (item.role === 'synchroscope') roles = ['sync/0', 'sync/1', 'sync/2', 'sync/3', 'sync/4', 'sync/5'];
        if (item.role === 'source') roles = item.ac
            ? ['source/ac/' + Java.loadClass('java.lang.Double').toHexString(Math.PI / 2)] : ['source/dc'];
        if (device) roles.forEach(function(role) { readings[role] = machineReading(device, role); });
        var tag = new (Java.loadClass('net.minecraft.nbt.CompoundTag'))();
        if (device) device.write(tag);
        return {row: item.row, role: item.role, kind: item.kind, x: item.x, y: item.y, z: item.z,
            state: String(machineLevel.getBlockState(machinePos(item))), settings: device ? String(tag) : null,
            blockEntity: be ? String(be.saveWithoutMetadata(machineLevel.registryAccess())) : null,
            shaftRpm: item.role === 'drive' || item.role === 'rotor' || item.kind === 'electroenergetics:white_electric_motor' ? be.getSpeed() : null,
            displayedFrequencyHz: item.role === 'frequency' ? be.electromagnetism$getFrequency() : null,
            phaseOffsetDegrees: item.role === 'synchroscope' ? be.electromagnetism$getPhaseOffset() : null,
            validPhaseOrder: item.role === 'synchroscope' ? be.electromagnetism$isValidConnection() : null,
            readings: readings};
    }),
    wiring: machineWires.map(function(wire) {
        var a = new MachineNode(wire.pinA, machinePos(wire.a)), b = new MachineNode(wire.pinB, machinePos(wire.b));
        return {row: wire.a.row, a: [wire.a.x, wire.a.z, wire.pinA], b: [wire.b.x, wire.b.z, wire.pinB], connected: machineData.isConnected(a, b)};
    })});
