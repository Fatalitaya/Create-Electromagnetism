package com.electromagnetism.simulation;

import static com.electromagnetism.simulation.SimulationProtocol.*;

/**
 * Propagate native and contract errors as unavailable readings so instruments cannot display a
 * failure as zero.
 */
public final class SimulationFailure extends IllegalStateException {
    private final Diagnostic diagnostic;
    public SimulationFailure(Status status, String reason) {
        this(new Diagnostic(status,reason));
    }
    public SimulationFailure(Diagnostic diagnostic) {
        super(diagnostic.reason());
        this.diagnostic=diagnostic;
    }
    public Diagnostic diagnostic() { return diagnostic; }
}
