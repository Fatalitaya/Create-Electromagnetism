package com.electromagnetism.simulation;

import java.util.UUID;
import static com.electromagnetism.simulation.SimulationProtocol.*;

/** JNI implementation owns handles; calls for a session must be serialized.
 * Inputs must be copied before asynchronous use. destroy must await cooperative cancellation.
 * No fallback to CEE is permitted. JniSimulationBackend implements this contract.
 */
public interface SimulationBackend {
    default boolean exchangeEnergy(Revision revision,UUID branch,double joules,double maximumJoules,boolean commit){return false;}
    Diagnostic availability();
    UUID create();
    void applyTopology(Topology topology);
    void applyBoundary(Topology topology);
    default void setInterval(IntervalInputs inputs) { throw new SimulationFailure(Status.UNSUPPORTED,"Interval inputs unsupported"); }
    void setParameters(Parameters parameters);
    Accepted advanceTo(Advance request);
    Observations readObservations(Revision revision);
    void acknowledgeEvents(Revision revision, java.util.List<UUID> eventIds);
    default void acknowledgeDestruction(Revision revision, java.util.List<UUID> eventIds, java.util.List<UUID> destroyedBranches) {
        if(!destroyedBranches.isEmpty())throw new SimulationFailure(SimulationProtocol.Status.UNSUPPORTED,"Assembly destruction requires a native energy boundary");
        acknowledgeEvents(revision,eventIds);
    }
    void subscribeWaveforms(WaveformSubscription subscription);
    Snapshot snapshot(Revision revision);
    void restore(UUID session, Snapshot snapshot);
    void destroy(UUID session);
}
