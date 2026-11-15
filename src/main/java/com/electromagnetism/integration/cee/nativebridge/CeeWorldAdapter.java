package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.SimulationProtocol.*;
import java.util.List;

/** Explicit replacement for the two CEE simulation events and device broadcast callbacks. */
public interface CeeWorldAdapter {
    Topology captureTopology(Revision revision);
    Parameters captureParameters(Revision revision);
    /** Called on the world thread only after checking session, revisions, incarnation and sequence. */
    void applyObservations(Observations observations);
    /** Includes panels/attachments, transient contact branches and third-party capability gaps. */
    List<String> unsupportedCapabilities();
}
