package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.SimulationProtocol.*;
import java.util.List;

/** Connect the CEE world to native sessions through explicit capture and delivery. */
public interface CeeWorldAdapter {
    Topology captureTopology(Revision revision);
    Parameters captureParameters(Revision revision);
    /** Check session, revision, instance and sequence on the world thread before calling. */
    void applyObservations(Observations observations);
    /** Include panel attachments, transient contacts and external devices that lack adapters. */
    List<String> unsupportedCapabilities();
}
