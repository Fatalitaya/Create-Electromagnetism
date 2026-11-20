package com.electromagnetism.simulation;

import java.util.UUID;
import static com.electromagnetism.simulation.SimulationProtocol.*;

/**
 * Serialize transactions within a session. Copy inputs before asynchronous execution and await
 * cooperative cancellation before releasing handles.
 */
public interface SimulationBackend {
    default boolean exchangeEnergy(Revision revision,UUID branch,double joules,double maximumJoules,boolean commit){return false;}
    Diagnostic availability();
    UUID create();
    default void repartition(Repartition request){throw new SimulationFailure(Status.UNSUPPORTED,"Session repartition unsupported");}
    void applyTopology(Topology topology);
    void applyBoundary(Topology topology);
    default void setInterval(IntervalInputs inputs) { throw new SimulationFailure(Status.UNSUPPORTED,"Interval inputs unsupported"); }
    void setParameters(Parameters parameters);
    default java.util.Optional<Observations> pollTick(TickRequest tick) {
        return java.util.Optional.of(advanceTick(tick));
    }
    default Observations advanceTick(TickRequest tick) { throw new SimulationFailure(Status.UNSUPPORTED,"Tick transactions unsupported"); }
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
