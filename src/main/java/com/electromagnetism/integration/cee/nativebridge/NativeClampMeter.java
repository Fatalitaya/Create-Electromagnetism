package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.SimulationProtocol.Reading;
import com.george_vi.electroenergetics.foundation.nodes.InWorldNodeConnection;
import com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData;
import net.minecraft.server.level.ServerLevel;
import java.util.Optional;
import java.util.UUID;
import java.util.OptionalDouble;

/** Read accepted RMS current on one conductor without loading it. */
public final class NativeClampMeter {
    private NativeClampMeter() {}
    public static Optional<Reading> read(ServerLevel level, InWorldNodeConnection connection) {
        var infrastructure=InfrastructureSavedData.load(level);
        return conductor(level,connection,infrastructure).flatMap(((CeeSimulationAccess)infrastructure.ticker)::electromagnetism$reading)
                .filter(r->r.windowEnd()>r.windowStart() && Double.isFinite(r.rmsCurrent()));
    }
    public static OptionalDouble displayCurrent(ServerLevel level, InWorldNodeConnection connection) {
        var infrastructure=InfrastructureSavedData.load(level);
        var branch=conductor(level,connection,infrastructure);
        return branch.isEmpty()?OptionalDouble.empty():((CeeSimulationAccess)infrastructure.ticker).electromagnetism$displayCurrent(branch.get());
    }
    private static Optional<UUID> conductor(ServerLevel level, InWorldNodeConnection connection, InfrastructureSavedData infrastructure) {
        if(connection==null || !level.isLoaded(connection.node1().sourcePos()) || !level.isLoaded(connection.node2().sourcePos()))return Optional.empty();
        if(!infrastructure.getConnections(connection.node1()).contains(connection))return Optional.empty();
        var wire=infrastructure.getConnectionData(connection);
        // The field around multiple cores depends on the sum of instantaneous currents. Individual
        // RMS magnitudes cannot reconstruct it.
        if(wire==null || wire.wireType().isDecorative() || !(wire instanceof CeeWireIdentity identity) || !identity.electromagnetism$wireIdentityProblem().isEmpty())return Optional.empty();
        var geometry=infrastructure.wireSimulationState.getConnection(connection);
        if(geometry!=null && !geometry.cuts.isEmpty())return Optional.empty(); // Current can differ between segments of a tapped conductor, so the measurement needs a location.
        return Optional.of(identity.electromagnetism$wireIdentity());
    }
}
