package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.SimulationProtocol.Reading;
import com.george_vi.electroenergetics.foundation.nodes.InWorldNodeConnection;
import com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData;
import net.minecraft.server.level.ServerLevel;
import java.util.Optional;

/** A non-invasive single-conductor RMS probe. No voltage/resistance reconstruction. */
public final class NativeClampMeter {
    private NativeClampMeter() {}
    public static Optional<Reading> read(ServerLevel level, InWorldNodeConnection connection) {
        if(connection==null || !level.isLoaded(connection.node1().sourcePos()) || !level.isLoaded(connection.node2().sourcePos()))return Optional.empty();
        var infrastructure=InfrastructureSavedData.load(level);
        if(!infrastructure.getConnections(connection.node1()).contains(connection))return Optional.empty();
        var wire=infrastructure.getConnectionData(connection);
        // A cable sheath encloses multiple currents. Individual RMS values cannot
        // be added to reconstruct their simultaneous magnetic sum.
        if(wire==null || wire.wireType().isDecorative() || !(wire instanceof CeeWireIdentity identity) || !identity.electromagnetism$wireIdentityProblem().isEmpty())return Optional.empty();
        var geometry=infrastructure.wireSimulationState.getConnection(connection);
        if(geometry!=null && !geometry.cuts.isEmpty())return Optional.empty(); // A location is required when current differs along a tapped conductor.
        return ((CeeSimulationAccess)infrastructure.ticker).electromagnetism$reading(identity.electromagnetism$wireIdentity())
                .filter(r->r.windowEnd()>r.windowStart() && Double.isFinite(r.rmsCurrent()));
    }
}
