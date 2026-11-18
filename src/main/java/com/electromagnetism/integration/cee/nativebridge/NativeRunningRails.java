package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.SimulationProtocol.*;
import com.george_vi.electroenergetics.content.connector.ConnectorDevice;
import com.george_vi.electroenergetics.content.connector.InsulatorDevice;
import com.george_vi.electroenergetics.foundation.nodes.InWorldNode;
import com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.*;
import com.simibubi.create.content.trains.track.ITrackBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** The two bonded running rails form one conductor. Create owns the mechanical graph. */
final class NativeRunningRails {
    private static final double RAIL_OHMS_PER_METRE = 0.00005;
    private record Tap(double metres, String key, PortId terminal) {}
    private final Map<TrackEdge,List<Tap>> taps = new IdentityHashMap<>();
    private final Set<TrackGraph> fedGraphs = new HashSet<>();
    private final List<BlockPos> unloadedInsulators = new ArrayList<>();
    private final InfrastructureSavedData sd;
    private final List<DeviceInput> devices;
    private final List<Branch> branches;
    private final Set<DeviceId> identities;

    NativeRunningRails(InfrastructureSavedData sd, Map<InWorldNode,PortId> ports,
            List<DeviceInput> devices, List<Branch> branches, Set<DeviceId> identities) {
        this.sd = sd;
        this.devices = devices;
        this.branches = branches;
        this.identities = identities;
        for (var device : sd.deviceSD.getDevices()) {
            boolean feeder = device instanceof ConnectorDevice;
            if (!feeder && !(device instanceof InsulatorDevice)) continue;
            var pos = device.pos.above();
            if (!sd.level.isLoaded(pos)) {
                if (!feeder) unloadedInsulators.add(pos);
                continue;
            }
            var state = sd.level.getBlockState(pos);
            if (!(state.getBlock() instanceof ITrackBlock track)) continue;
            var axes = track.getTrackAxes(sd.level, pos, state);
            if (axes.size() != 1) continue;
            var location = TrackGraphHelper.getGraphLocationAt(sd.level, pos, Direction.AxisDirection.POSITIVE, axes.getFirst());
            if (location == null) continue;
            var from = location.graph.locateNode(location.edge.getFirst());
            var to = location.graph.locateNode(location.edge.getSecond());
            if (from == null || to == null) continue;
            var edge = location.graph.getConnectionsFrom(from).get(to);
            if (!conducts(edge)) continue;
            var port = ports.get(new InWorldNode(0, device.pos));
            if (feeder && port == null) continue;
            if (feeder) fedGraphs.add(location.graph);
            tap(location.graph, edge, location.position, "fitting/" + pos.asLong(), feeder ? port : null);
        }
    }

    private boolean conducts(TrackEdge edge) {
        if (edge == null || edge.isInterDimensional() || !sd.level.dimension().equals(edge.node1.getLocation().dimension)) return false;
        var material = edge.getTrackMaterial().id;
        return !material.getNamespace().equals("railways")
                || !(material.getPath().contains("phantom") || material.getPath().contains("monorail"));
    }

    private void tap(TrackGraph graph, TrackEdge edge, double metres, String key, PortId terminal) {
        if (edge.node1.getNetId() > edge.node2.getNetId()) {
            metres = edge.getLength() - metres;
            edge = graph.getConnectionsFrom(edge.node2).get(edge.node1);
        }
        if (edge != null) taps.computeIfAbsent(edge, unused -> new ArrayList<>()).add(new Tap(metres, key, terminal));
    }

    void wheels(Train train, PortId railBus) {
        if (train.derailed || !fedGraphs.contains(train.graph)) return;
        int[] index = {0};
        train.forEachTravellingPoint(point -> {
            String key = train.id + "/wheel/" + index[0]++;
            if (conducts(point.edge)) tap(train.graph, point.edge, point.position, key, railBus);
        });
    }

    private static UUID id(String key) {
        return UUID.nameUUIDFromBytes(("electromagnetism/running-rail/" + key).getBytes(StandardCharsets.UTF_8));
    }

    private PortId port(String key) {
        var owner = new DeviceId(id(key), 0);
        var port = new PortId(owner, 0);
        if (identities.add(owner)) devices.add(new DeviceInput(owner, "ports", List.of(port), Map.of()));
        return port;
    }

    private String nodeKey(TrackNode node) {
        var loc = node.getLocation();
        return loc.dimension.location() + "/" + loc.getX() + "/" + loc.getY() + "/" + loc.getZ() + "/" + loc.yOffsetPixels;
    }

    private void resistor(String key, PortId a, PortId b, double ohms) {
        branches.add(new Branch(id(key), a, b, "resistor", Map.of("resistanceOhms", ohms)));
    }

    // The device inventory and Create graph outlive chunk unloading. Preserve gaps on
    // straight running track without requesting the chunk or its block entity.
    private void addUnloadedGaps(TrackGraph graph, TrackEdge edge) {
        if (unloadedInsulators.isEmpty() || edge.isTurn()) return;
        var start = edge.node1.getLocation().getLocation();
        var delta = edge.node2.getLocation().getLocation().subtract(start);
        double horizontalLengthSqr = delta.horizontalDistanceSqr();
        if (horizontalLengthSqr == 0) return;
        for (var pos : unloadedInsulators) {
            var centre = Vec3.atBottomCenterOf(pos);
            var offset = centre.subtract(start);
            double fraction = (offset.x * delta.x + offset.z * delta.z) / horizontalLengthSqr;
            if (fraction < 0 || fraction > 1) continue;
            var difference = start.add(delta.scale(fraction)).subtract(centre);
            if (difference.horizontalDistanceSqr() < 1e-8 && difference.y >= -1e-6 && difference.y < 1)
                tap(graph, edge, fraction * edge.getLength(), "fitting/" + pos.asLong(), null);
        }
    }

    void finish() {
        for (var graph : fedGraphs) {
            for (var location : graph.getNodes()) {
                var from = graph.locateNode(location);
                for (var edge : graph.getConnectionsFrom(from).values()) {
                    if (from.getNetId() >= edge.node2.getNetId() || !conducts(edge)) continue;
                    String first = nodeKey(from), last = nodeKey(edge.node2);
                    addUnloadedGaps(graph, edge);
                    var points = taps.getOrDefault(edge, List.of());
                    if (!points.isEmpty()) points.sort(Comparator.comparingDouble(Tap::metres).thenComparing(Tap::key));
                    PortId previous = port(first);
                    double distance = 0;
                    for (var point : points) {
                        if (point.terminal() == null) {
                            previous = null; // An insulator under the track cuts this segment.
                        } else {
                            var contact = port("tap/" + point.key());
                            resistor("contact/" + point.key(), contact, point.terminal(), 0.01);
                            if (previous != null) segment(previous, contact, point.metres() - distance);
                            previous = contact;
                        }
                        distance = point.metres();
                    }
                    if (previous != null) segment(previous, port(last), edge.getLength() - distance);
                }
            }
        }
    }

    private void segment(PortId a, PortId b, double metres) {
        var left = a.device().instance();
        var right = b.device().instance();
        String key = left.compareTo(right) < 0 ? left + "/" + right : right + "/" + left;
        resistor(key, a, b, Math.max(0.00001, metres * RAIL_OHMS_PER_METRE));
    }
}
