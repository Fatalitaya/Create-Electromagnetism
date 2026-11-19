// Read-only runtime progress check; no reflection or player operations.
var syncServer = Java.loadClass('net.neoforged.neoforge.server.ServerLifecycleHooks').getCurrentServer();
var syncData = Java.loadClass('com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData').load(syncServer.overworld());
var syncProgress = syncData.ticker.electromagnetism$nativeProgress();
var syncResult = {status: String(syncData.ticker.electromagnetism$nativeStatus()), caughtUp: false,
    playerModified: false, domains: [], maxConcurrentSolves: 0, totalSolveMs: 0, slowestSolveMs: 0};
if (syncProgress.isPresent()) {
    var report = syncProgress.get();
    syncResult.tick = Number(report.tick());
    syncResult.caughtUp = String(report.diagnostic().status()) === 'READY';
    var endpoints = [];
    report.domains().forEach(function(domain) {
        var completed = Number(domain.lastCompletedTick()) === syncResult.tick;
        syncResult.caughtUp = syncResult.caughtUp && completed && Number(domain.consecutiveSkips()) === 0;
        var duration = Number(domain.lastSolveNanos()) / 1e6;
        var start = Number(domain.lastSolveStartNanos()) / 1e6;
        syncResult.domains.push({session: String(domain.session()), completed: completed,
            lastCompletedTick: Number(domain.lastCompletedTick()),
            completedTicks: Number(domain.completedTicks()), attemptedTicks: Number(domain.attemptedTicks()),
            consecutiveSkips: Number(domain.consecutiveSkips()),
            status: String(domain.diagnostic().status()), worker: Number(domain.lastWorkerId()), lastSolveMs: duration});
        if (completed && duration > 0) {
            endpoints.push([start, 1], [start + duration, -1]);
            syncResult.totalSolveMs += duration;
            syncResult.slowestSolveMs = Math.max(syncResult.slowestSolveMs, duration);
        }
    });
    endpoints.sort(function(a, b) { return a[0] - b[0] || a[1] - b[1]; });
    var active = 0;
    endpoints.forEach(function(point) {
        active += point[1];
        syncResult.maxConcurrentSolves = Math.max(syncResult.maxConcurrentSolves, active);
    });
}
JSON.stringify(syncResult);
