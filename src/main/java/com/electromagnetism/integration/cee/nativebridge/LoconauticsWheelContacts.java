package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.SimulationProtocol.PortId;
import com.lycoris.loconautics.trainphysics.state.SableTrainRegistry;
import com.simibubi.create.Create;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import java.util.Map;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

final class LoconauticsWheelContacts {
    static void capture(ServerLevel level, Map<BlockPos, List<PortId>> bogeys, NativeRunningRails rails) {
        var container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        for (var train : SableTrainRegistry.all()) {
            if (train.level() != level || train.car() == null) continue;
            var car = train.car();
            var body = container.getSubLevel(car.subLevelId());
            if (body == null) continue;
            var origin = body.getPlot().getCenterBlock();
            for (var bogey : car.bogeys()) {
                var terminals = bogeys.remove(origin.offset(bogey.localPos()));
                var rail = bogey.rail();
                if (terminals == null || train.isDerailed() || rail == null || !rail.edgesValid() || rail.railHostMissing()) continue;
                var graph = Create.RAILWAYS.trackNetworks.get(rail.graphId());
                var point = rail.seedPoint();
                if (graph != null && point != null)
                    rails.wheelTerminal(graph, point.edge, point.position, terminals);
            }
        }
    }
}
