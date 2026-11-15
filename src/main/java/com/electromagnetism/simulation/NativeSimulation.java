package com.electromagnetism.simulation;

import static com.electromagnetism.simulation.SimulationProtocol.*;

/** Fail-closed transition status. This is not a native solver implementation. */
public final class NativeSimulation {
    private NativeSimulation() {}
    public static final Diagnostic UNAVAILABLE = new Diagnostic(Status.UNAVAILABLE,
            "Native world coordinator is not connected; legacy Java/CEE physics is disabled");
    public static UnsupportedOperationException legacyCall(String operation) {
        return new UnsupportedOperationException(operation + ": legacy Java/CEE physics is disabled; physical state belongs to the native backend");
    }
}
