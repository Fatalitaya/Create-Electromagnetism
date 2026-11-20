package com.electromagnetism.simulation;

import org.slf4j.LoggerFactory;
import static com.electromagnetism.simulation.SimulationProtocol.*;

/** Log the first failure and changes of cause. Repeated identical failures share one diagnostic. */
public final class SimulationFaultLog {
    private Diagnostic reported;

    public synchronized void report(String context, Diagnostic diagnostic, Throwable cause) {
        if (diagnostic.equals(reported)) return;
        reported = diagnostic;
        LoggerFactory.getLogger("Electromagnetism/Simulation").error(
                context + ": " + diagnostic.status() + ": " + diagnostic.reason(),
                cause == null ? new SimulationFailure(diagnostic.status(), diagnostic.reason()) : cause);
    }

    public synchronized void recovered() { reported = null; }
}
