// Server-side AutoJS fixture. setup -> later tick: connect -> later ticks: observe.
// Separate from cee-devices-scene.js; never clears or replaces an occupied volume.
var machineAction = typeof ceeSceneAction === 'undefined' ? 'observe' : ceeSceneAction;
var machineActions = ['setup', 'connect', 'observe', 'start', 'stop', 'input-low', 'input-high', 'input-normal', 'load-light', 'load-heavy', 'load-normal'];
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
var machineRows = [
    {name: 'transformer_down', z: 10, expected: '20 Hz AC; Np/Ns=2; secondary RMS approximately half primary, with winding/wire losses.'},
    {name: 'transformer_up', z: 18, expected: '20 Hz AC; Np/Ns=0.5; secondary RMS approximately twice primary, with winding/wire losses.'},
    {name: 'regulator', z: 26, expected: '12 V DC target; 11/12/14 V input should regulate near 12 V after settling; tap range 0.8..1.2.'},
    {name: 'generator_single_phase', z: 34, expected: 'Single-phase sine AC, permanent stator, 32 rpm drive; frequency 32/60 Hz; voltage changes polarity and decays after stop.'},
    {name: 'generator_three_phase', z: 42, expected: 'Three equal phase-neutral loads; similar RMS voltage/current and positive power; nominal frequency abs(rpm)/60 Hz.'},
    {name: 'motor', z: 50, expected: '12 V DC through 120 ohm ballast; finite winding current and shaft response; current decays after stop.'},
    {name: 'bridge_rectifier', z: 58, expected: '20 Hz, 12 V peak AC; full-wave pulsating positive load voltage, with two diode drops; no smoothing capacitor.'}
];
var machineItems = [], machineWires = [];
function machinePos(item) { return new MachinePos(item.x, -59, item.z); }
function machineDevice(item) { return machineData.deviceSD.getDevice(machinePos(item)); }
function machineEntity(item) { return machineLevel.getBlockEntity(machinePos(item)); }
function machineItem(row, role, x, dz, kind, properties, electrical) {
    var item = {row: row.name, role: role, x: x, z: row.z + dz,
        kind: kind.indexOf(':') < 0 ? 'electroenergetics:' + kind : kind,
        properties: properties || {}, electrical: electrical !== false};
    machineItems.push(item);
    return item;
}
function machineLink(a, pinA, b, pinB) { machineWires.push({a: a, pinA: pinA, b: b, pinB: pinB}); }
machineRows.forEach(function(row) {
    var source, dut, load;
    if (row.name.indexOf('generator_') === 0) {
        row.drive = machineItem(row, 'drive', 87, 0, 'create:creative_motor', {facing: 'east'}, false);
        row.rotor = machineItem(row, 'rotor', 88, 0, 'alternator_rotor', {axis: 'x'}, false);
        row.stator = machineItem(row, 'stator', 88, 1, 'stator', {facing: 'north'}, false);
        row.brush = machineItem(row, 'brush', 89, 0,
            row.name === 'generator_single_phase' ? 'alternator_brushes' : 'three_phase_alternator_brushes', {facing: 'east'});
        for (var phase = 1; phase <= (row.name === 'generator_single_phase' ? 1 : 3); phase++) {
            load = machineItem(row, 'load_' + phase, 96, (phase - 1) * 2, 'creative_resistor');
            machineLink(row.brush, phase, load, 0);
            machineLink(load, 1, row.brush, 0); // Neutral is pin 0, phases are pins 1..3.
        }
        return;
    }
    var ac = row.name.indexOf('transformer_') === 0 || row.name === 'bridge_rectifier';
    source = row.source = machineItem(row, 'source', 80, 0, 'creative_battery', {ac: String(ac)});
    source.ac = ac;
    load = machineItem(row, row.name === 'motor' ? 'ballast' : 'load', 96, 0, 'creative_resistor');
    if (row.name.indexOf('transformer_') === 0) {
        dut = row.dut = machineItem(row, 'dut', 88, 0, 'transformer');
        machineLink(source, 0, dut, 0); machineLink(source, 1, dut, 1);
        machineLink(dut, 2, load, 0); machineLink(load, 1, dut, 3);
    } else if (row.name === 'regulator') {
        dut = row.dut = machineItem(row, 'dut', 88, 0, 'voltage_regulator', {top: 'true', bottom: 'true', sliced: 'false'});
        machineLink(source, 0, dut, 0); machineLink(source, 1, dut, 2);
        machineLink(dut, 1, load, 0); machineLink(load, 1, dut, 2);
    } else if (row.name === 'motor') {
        dut = row.dut = machineItem(row, 'dut', 88, 0, 'white_electric_motor');
        machineLink(source, 0, load, 0); machineLink(load, 1, dut, 0); machineLink(dut, 1, source, 1);
    } else {
        // CEE diode: anode pin 1 -> cathode pin 0.
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
// Preflight the whole reserved volume, including supports and rotor neighbours.
for (var mx = 78; mx <= 100; mx++) for (var mz = 8; mz <= 63; mz++) {
    for (var my = -60; my <= -57; my++) {
        var check = new MachinePos(mx, my, mz);
        if (!machineLevel.isLoaded(check)) throw new Error('Unloaded scene volume: ' + check);
        if (machineAction === 'setup' && !machineLevel.isEmptyBlock(check)) throw new Error('Occupied scene volume: ' + check + '; use observe');
    }
}
machineItems.forEach(function(item) {
    if (!MachineRegistry.BLOCK.containsKey(MachineId.parse(item.kind))) throw new Error('Missing registry entry: ' + item.kind);
    item.state = machineState(item); // Validate every state before placing any blocks.
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
    for (var mx = 78; mx <= 100; mx++) for (var mz = 8; mz <= 63; mz++) machineLevel.setBlockAndUpdate(new MachinePos(mx, -60, mz), floor);
    // Wall-mounted components use their default north-facing orientation.
    machineItems.forEach(function(item) {
        if (item.electrical) machineLevel.setBlockAndUpdate(machinePos(item).south(), floor);
    });
    machineItems.forEach(function(item) {
        var block = item.state.getBlock(), at = machinePos(item);
        machineLevel.setBlockAndUpdate(at, item.state);
        block.setPlacedBy(machineLevel, at, item.state, null, new MachineStack(block.asItem()));
        if (item.electrical && !machineDevice(item)) throw new Error('Device registration failed: ' + at);
        if (item.role === 'source') machineVoltage(item, 12);
        if (item.role.indexOf('load') === 0 || item.role === 'ballast') machineLoad(item, 120);
        if (item.role === 'drive') machineDrive(item, 0);
    });
    machineRows.forEach(function(row) {
        if (row.name.indexOf('transformer_') === 0) {
            // Upstream ratio table: default index 42 = 1:1; 29 = 2:1; 55 = 1:2.
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
    // Check every endpoint and reject connected/partially connected reruns before any write.
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
} else if (machineAction === 'start' || machineAction === 'stop') {
    machineRows.forEach(function(row) {
        if (row.drive) machineDrive(row.drive, machineAction === 'start' ? 32 : 0);
        if (row.source) machineVoltage(row.source, machineAction === 'start' ? 12 : 0);
    });
} else if (machineAction.indexOf('input-') === 0) {
    machineVoltage(machineRows[2].source, machineAction === 'input-low' ? 11 : machineAction === 'input-high' ? 14 : 12);
} else if (machineAction.indexOf('load-') === 0) {
    // Ballast stays at 120 ohms to limit motor stall current.
    machineItems.forEach(function(item) {
        if (item.role.indexOf('load') === 0) machineLoad(item, machineAction === 'load-light' ? 240 : machineAction === 'load-heavy' ? 60 : 120);
    });
}
if (machineAction !== 'observe') { machineData.deviceSD.setDirty(); machineData.setDirty(); }
function machineReading(device, role) {
    var optional = machineData.ticker.electromagnetism$reading(MachineWorld.branchId(device, role));
    if (!optional.isPresent()) return null;
    var r = optional.get();
    return {meanVolts: r.meanVoltage(), rmsVolts: r.rmsVoltage(), peakVolts: r.peakVoltage(),
        meanAmps: r.meanCurrent(), rmsAmps: r.rmsCurrent(), watts: r.activePower(),
        absorbedJoules: r.totalAbsorbedEnergy(), returnedJoules: r.totalReturnedEnergy(),
        frequencyHz: r.frequency().isPresent() ? r.frequency().getAsDouble() : null,
        torqueNewtonMetres: r.torqueNewtonMetres().isPresent() ? r.torqueNewtonMetres().getAsDouble() : null,
        windowStart: r.windowStart(), windowEnd: r.windowEnd()};
}
var machineProgress = machineData.ticker.electromagnetism$nativeProgress();
JSON.stringify({action: machineAction, dimension: 'minecraft:overworld', deviceY: -59,
    gameTime: machineLevel.getGameTime(), linksCreated: machineLinksCreated, expectedLinks: machineWires.length,
    status: String(machineData.ticker.electromagnetism$nativeStatus()), progress: String(machineProgress),
    caughtUp: machineProgress.isPresent() && machineProgress.get().backlogSeconds() === 0,
    blockers: String(machineData.ticker.electromagnetism$inspectInputs().blockers()),
    note: 'Observations are not a pass verdict. Compare later accepted windows after settling; null means unavailable, not zero.',
    scenarios: machineRows.map(function(row) { return {name: row.name, z: row.z, expected: row.expected}; }),
    devices: machineItems.map(function(item) {
        var device = item.electrical ? machineDevice(item) : null, be = machineEntity(item), readings = {};
        var roles = ['main'];
        if (item.kind === 'electroenergetics:transformer') roles = ['primary-voltage', 'secondary-voltage', 'primary-meter', 'secondary-meter', 'winding-heat/0', 'winding-heat/1'];
        if (item.kind === 'electroenergetics:voltage_regulator') roles = ['regulator/input-voltage', 'regulator/output-voltage', 'regulator/input-copper', 'regulator/output-copper'];
        if (item.role === 'brush') roles = []; // Sources belong to rotor assembly UUIDs, not brush/main.
        if (item.role === 'source') roles = item.ac
            ? ['source/ac/' + Java.loadClass('java.lang.Double').toHexString(Math.PI / 2)] : ['source/dc'];
        if (device) roles.forEach(function(role) { readings[role] = machineReading(device, role); });
        var tag = new (Java.loadClass('net.minecraft.nbt.CompoundTag'))();
        if (device) device.write(tag);
        return {row: item.row, role: item.role, kind: item.kind, x: item.x, z: item.z,
            state: String(machineLevel.getBlockState(machinePos(item))), settings: device ? String(tag) : null,
            blockEntity: be ? String(be.saveWithoutMetadata(machineLevel.registryAccess())) : null,
            shaftRpm: item.role === 'drive' || item.role === 'rotor' || item.kind === 'electroenergetics:white_electric_motor' ? be.getSpeed() : null,
            readings: readings};
    }),
    wiring: machineWires.map(function(wire) {
        var a = new MachineNode(wire.pinA, machinePos(wire.a)), b = new MachineNode(wire.pinB, machinePos(wire.b));
        return {row: wire.a.row, a: [wire.a.x, wire.a.z, wire.pinA], b: [wire.b.x, wire.b.z, wire.pinB], connected: machineData.isConnected(a, b)};
    })});
