// Run on the server through cee-basic-autojs.ps1. Uses a separate native owner;
// the captured world, its wiring and its accepted energy are never modified.
// Expected: contradictory sources fail one tick; the next valid tick reaches
// 0.05 native seconds with 0.05 excluded seconds and no retained input.
var recoveryServer = Java.loadClass('net.neoforged.neoforge.server.ServerLifecycleHooks').getCurrentServer();
var recoveryData = Java.loadClass('com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData').load(recoveryServer.overworld());
var RecoveryWorld = Java.loadClass('com.electromagnetism.integration.cee.nativebridge.CeeNativeWorld');
var recoveryFrame = new RecoveryWorld(recoveryData).capture();
var RecoveryBackend = Java.loadClass('com.electromagnetism.simulation.JniSimulationBackend');
var RecoveryCoordinator = Java.loadClass('com.electromagnetism.simulation.SimulationCoordinator');
var RecoveryFrame = Java.loadClass('com.electromagnetism.simulation.SimulationCoordinator$Frame');
var RecoveryBranch = Java.loadClass('com.electromagnetism.simulation.SimulationProtocol$Branch');
var RecoveryList = Java.loadClass('java.util.ArrayList');
var RecoveryMap = Java.loadClass('java.util.HashMap');
var RecoveryUUID = Java.loadClass('java.util.UUID');
var recoveryBranches = new RecoveryList(recoveryFrame.branches());
var recoverySource = null;
recoveryBranches.forEach(function(branch) { if (String(branch.model()) === 'dc_voltage_source') recoverySource = branch; });
if (recoverySource === null) throw new Error('Scene requires a DC voltage source');
var recoveryParameters = new RecoveryMap(recoverySource.parameters());
recoveryParameters.put('voltageVolts', recoverySource.parameters().get('voltageVolts') + 1.0);
recoveryBranches.add(new RecoveryBranch(RecoveryUUID.randomUUID(), recoverySource.positive(), recoverySource.negative(), 'dc_voltage_source', recoveryParameters));
var recoveryBad = new RecoveryFrame(recoveryFrame.devices(), recoveryBranches, recoveryFrame.blockers(), recoveryFrame.removalDisposition());
var recoveryMetadata = Java.loadClass('net.neoforged.fml.ModList').get().getModContainerById('electromagnetism').orElseThrow().getModInfo().getVersion().toString();
var recoveryBackend = RecoveryBackend.adaptivePackaged(recoveryMetadata);
var recoveryOwner = new RecoveryCoordinator(recoveryBackend, function(task) { task.run(); }, 10000, 1000000000);
var recoveryResult = {worldModified:false, expected:'failure expires at next tick; accepted=0.05, excluded=0.05, queue=0'};
try {
    recoveryOwner.tick(recoveryBad);
    recoveryOwner.finishTick(recoveryBad, function(observations) { return new RecoveryList(); });
    recoveryResult.failedStatus = String(recoveryOwner.status());
    recoveryResult.failedProgress = String(recoveryOwner.progress());
    recoveryOwner.tick(recoveryFrame);
    recoveryOwner.finishTick(recoveryFrame, function(observations) { return new RecoveryList(); });
    recoveryResult.recoveredStatus = String(recoveryOwner.status());
    recoveryResult.recoveredProgress = String(recoveryOwner.progress());
    recoveryResult.passed = String(recoveryOwner.status().status()) === 'READY'
        && Math.abs(recoveryOwner.acceptedTime() - 0.05) < 1e-12
        && Math.abs(recoveryOwner.progress().excludedSeconds() - 0.05) < 1e-12
        && recoveryOwner.progress().queuedBoundaries() === 0;
    // Repeat after physical heat has accumulated, then restore a checkpoint
    // containing the failed tick. Accepted native state must survive both.
    recoveryOwner.tick(recoveryBad);
    recoveryOwner.finishTick(recoveryBad, function(observations) { return new RecoveryList(); });
    recoveryResult.failedAfterAcceptance = String(recoveryOwner.progress());
    var recoverySaved = recoveryOwner.checkpoint(recoveryFrame, function(observations) { return new RecoveryList(); });
    var recoveryHistory = recoveryOwner.inputHistoryCheckpoint();
    recoveryOwner.close();
    recoveryOwner = new RecoveryCoordinator(recoveryBackend, function(task) { task.run(); }, 10000, 1000000000, recoverySaved, recoveryHistory);
    recoveryOwner.tick(recoveryFrame);
    recoveryOwner.finishTick(recoveryFrame, function(observations) { return new RecoveryList(); });
    recoveryResult.restoredProgress = String(recoveryOwner.progress());
    recoveryResult.passed = recoveryResult.passed
        && Math.abs(recoverySaved.acceptedTime() - 0.05) < 1e-12
        && String(recoveryOwner.status().status()) === 'READY'
        && Math.abs(recoveryOwner.acceptedTime() - 0.1) < 1e-12
        && Math.abs(recoveryOwner.progress().excludedSeconds() - 0.1) < 1e-12
        && recoveryOwner.progress().queuedBoundaries() === 0;
} finally {
    recoveryOwner.close();
    recoveryBackend.close();
}
JSON.stringify(recoveryResult);
