package com.electromagnetism.simulation;

import static com.electromagnetism.simulation.SimulationProtocol.*;

/** A diagnosed native/contract failure. Callers must publish unavailability, never zero readings. */
public final class SimulationFailure extends IllegalStateException {
    private final Diagnostic diagnostic;
    public SimulationFailure(Status status, String reason) {
        super(reason);
        diagnostic = new Diagnostic(status, reason);
    }
    public Diagnostic diagnostic() { return diagnostic; }
}
