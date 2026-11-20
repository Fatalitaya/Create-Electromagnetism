package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.config.ModConfigs;
import com.electromagnetism.simulation.SimulationProtocol.*;
import com.george_vi.electroenergetics.CEEWireTypes;
import com.george_vi.electroenergetics.foundation.nodes.*;
import com.george_vi.electroenergetics.simulation.infrastructure.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Capture wire geometry from the world. Rust solves current and maintains one thermal state per
 * conductor.
 */
public final class NativeConductors {
    private NativeConductors(){}
    public record Capture(Map<Node,PortId> taps,Set<UUID> catenary){}
    public static Capture capture(InfrastructureSavedData sd,Map<InWorldNode,PortId> ports,Set<DeviceId> identities,List<DeviceInput> devices,List<Branch> branches,Map<UUID,InWorldNodeConnection> owners,Map<Node,UUID> stableTaps,Map<DeviceId,List<String>> blockers){
        var taps=new HashMap<Node,PortId>();var catenary=new HashSet<UUID>();
        for(var entry:sd.wireSimulationState.getAllConnections()) {
            var connection=entry.getKey();var geometry=entry.getValue();var wire=geometry.wireData;
            int firstBranch=branches.size(),firstDevice=devices.size();
            try {
                if(!(wire instanceof CeeWireIdentity identity) || !identity.electromagnetism$wireIdentityProblem().isEmpty())throw new IllegalArgumentException("Conductor identity unavailable");
                UUID id=identity.electromagnetism$wireIdentity();
                NativeWireAttachments.validate(wire);
                // The duplex sheath contains two electrically isolated branches.
                if(wire.wireType().isDecorative() && wire.wireType()!=CEEWireTypes.DUPLEX.get())continue;
                PortId a=endpoint(sd,connection.node1(),ports,identities,devices),b=endpoint(sd,connection.node2(),ports,identities,devices);
                double length=wire.length;
                if(!Double.isFinite(length)||length<0)throw new IllegalArgumentException("Conductor length must be finite and nonnegative");
                if(length==0)length=geometry.pos1.distanceTo(geometry.pos2);
                if(!Double.isFinite(length)||length<=0)throw new IllegalArgumentException("Conductor length must be positive");
                if(wire.wireType()==CEEWireTypes.DUPLEX.get()) {
                    if(!geometry.cuts.isEmpty())throw new IllegalArgumentException("A cable sheath contact has no identified conductor");
                    for(int conductor=1;conductor<=2;conductor++) {
                        var first=ports.get(new InWorldNode(connection.node1().id()+conductor,connection.node1().sourcePos()));
                        var second=ports.get(new InWorldNode(connection.node2().id()+conductor,connection.node2().sourcePos()));
                        if(first==null||second==null)throw new IllegalArgumentException("Duplex endpoint missing");
                        UUID strand=strandId(id,conductor);
                        branches.add(new Branch(strand,first,second,"thermal_fuse",thermal(CEEWireTypes.BUNDLE_CONDUCTOR.get().getResistance()*length,length)));
                        if(owners.putIfAbsent(strand,connection)!=null)throw new IllegalArgumentException("Duplicate conductor identity");
                    }
                    continue;
                }
                double ohms=wire.wireType().getResistance()*length;
                if(!Double.isFinite(ohms)||ohms<=0)throw new IllegalArgumentException("Conductor resistance must be positive and finite");
                var parameters=new HashMap<>(thermal(Math.max(1e-5,ohms),length));
                var extra=new ArrayList<PortId>();var positions=new ArrayList<Double>();positions.add(0.0);positions.add(1.0);
                var cuts=geometry.cuts.stream().sorted(Comparator.comparingDouble(WireSimulationState.CutWireEntry::point).thenComparing(c->{var semantic=stableTaps.get(c.node());if(semantic==null)throw new IllegalArgumentException("Unidentified conductor cut owner");return semantic;})).toList();
                var groups=new HashMap<Float,PortId>();
                for(var cut:cuts) {
                    float fraction=cut.point();if(!Float.isFinite(fraction)||fraction<0||fraction>1)throw new IllegalArgumentException("Invalid conductor contact position");
                    PortId port=fraction==0?a:fraction==1?b:groups.get(fraction);
                    if(port==null) {
                        if(extra.size()>=30)throw new IllegalArgumentException("Conductor contact count exceeds 30");
                        var semantic=stableTaps.get(cut.node());
                        if(semantic==null)throw new IllegalArgumentException("No stable owner for conductor cut: "+cut.handle().name);
                        UUID instance=UUID.nameUUIDFromBytes((id+"/tap/"+semantic).getBytes(StandardCharsets.UTF_8));
                        var owner=new DeviceId(instance,0);if(!identities.add(owner))throw new IllegalArgumentException("Duplicate conductor contact identity");
                        port=new PortId(owner,0);devices.add(new DeviceInput(owner,"ports",List.of(port),Map.of()));
                        groups.put(fraction,port);extra.add(port);positions.add((double)fraction);
                    }
                    var previous=taps.put(cut.node(),port);if(previous!=null&&!previous.equals(port))throw new IllegalArgumentException("Contact node belongs to two conductors");
                }
                if(extra.isEmpty())branches.add(new Branch(id,a,b,"thermal_fuse",parameters));
                else {
                    parameters.put("portCount",(double)(2+extra.size()));for(int i=0;i<32;i++)parameters.put("position"+i,i<positions.size()?positions.get(i):0.0);
                    branches.add(new Branch(id,a,b,"tapped_wire",parameters,extra));
                }
                if(owners.putIfAbsent(id,connection)!=null)throw new IllegalArgumentException("Duplicate conductor identity");
                if(geometry.isCatenary)catenary.add(id);
            } catch(IllegalArgumentException | IllegalStateException invalid) {
                var reason=List.of("Invalid conductor "+connection+": "+invalid.getMessage());
                boolean located=false;
                for(var node:List.of(connection.node1(),connection.node2())) {
                    var port=ports.get(node);
                    if(port!=null){blockers.put(port.device(),reason);located=true;}
                    var device=sd.deviceSD.getDevice(node.sourcePos());
                    if(device instanceof CeeDeviceIdentity owner){blockers.put(owner.electromagnetism$nativeIdentity(),reason);located=true;}
                }
                for(var input:devices.subList(firstDevice,devices.size()))blockers.put(input.id(),reason);
                var partial=branches.subList(firstBranch,branches.size());
                for(var branch:partial) {
                    blockers.put(branch.positive().device(),reason);
                    var other=owners.get(branch.id());
                    if(other!=null)for(var node:List.of(other.node1(),other.node2())) {
                        var port=ports.get(node);
                        if(port!=null)blockers.put(port.device(),reason);
                    }
                    owners.remove(branch.id(),connection);
                }
                partial.clear();
                if(!located)throw invalid;
            }
        }
        var cross=new ArrayList<it.unimi.dsi.fastutil.objects.ObjectDoublePair<DirectionalNodeConnection>>();
        sd.wireCrossContactModule.loadLazyConnections(cross);
        for(var entry:cross) {
            var connection=entry.left();
            var first=connection.node1();var second=connection.node2();
            var a=first instanceof InWorldNode node?ports.get(node):taps.get(first);
            var b=second instanceof InWorldNode node?ports.get(node):taps.get(second);
            double ohms=entry.rightDouble();
            if(a==null || b==null || !Double.isFinite(ohms) || ohms<=0) {
                var reason=List.of("Invalid crossing contact: "+connection);
                if(a!=null)blockers.put(a.device(),reason);
                if(b!=null)blockers.put(b.device(),reason);
                continue;
            }
            String one=a.toString(),two=b.toString();String key=one.compareTo(two)<0?one+"/"+two:two+"/"+one;
            UUID identity=UUID.nameUUIDFromBytes(("cross-contact/"+key).getBytes(StandardCharsets.UTF_8));
            branches.add(new Branch(identity,a,b,"resistor",Map.of("resistanceOhms",ohms)));
        }
        return new Capture(Map.copyOf(taps),Set.copyOf(catenary));
    }
    private static UUID strandId(UUID wire,int conductor) {
        return UUID.nameUUIDFromBytes((wire+"/conductor/"+conductor).getBytes(StandardCharsets.UTF_8));
    }
    static boolean stillOwns(InfrastructureSavedData sd,InWorldNodeConnection connection,UUID branch,boolean catenary) {
        for(var entry:sd.wireSimulationState.getAllConnections()) {
            if(!connection.equals(entry.getKey()) || entry.getValue().isCatenary!=catenary)continue;
            var wire=entry.getValue().wireData;
            if(!(wire instanceof CeeWireIdentity identity))continue;
            var id=identity.electromagnetism$wireIdentity();
            if(wire.wireType()==CEEWireTypes.DUPLEX.get())return branch.equals(strandId(id,1)) || branch.equals(strandId(id,2));
            return branch.equals(id);
        }
        return false;
    }
    private static PortId endpoint(InfrastructureSavedData sd,InWorldNode node,Map<InWorldNode,PortId> ports,Set<DeviceId> identities,List<DeviceInput> devices){
        var existing=ports.get(node);if(existing!=null)return existing;
        if(!sd.level.isLoaded(node.sourcePos()))throw new IllegalArgumentException("Conductor endpoint unloaded");
        if(node.id()!=0 || !(sd.level.getBlockEntity(node.sourcePos()) instanceof NativeCatenaryEndpoint holder))throw new IllegalArgumentException("Wire endpoint has no adapted owner: "+node);
        if(!holder.electromagnetism$endpointProblem().isEmpty())throw new IllegalArgumentException(holder.electromagnetism$endpointProblem());
        var id=new DeviceId(holder.electromagnetism$endpointId(),0);if(!identities.add(id))throw new IllegalArgumentException("Duplicate catenary endpoint identity");
        var port=new PortId(id,0);ports.put(node,port);devices.add(new DeviceInput(id,"ports",List.of(port),Map.of()));return port;
    }
    private static Map<String,Double> thermal(double ohms,double length){return Map.of("ambientOhms",ohms,"coefficientPerKelvin",0.0,
            "heatCapacityJoulesPerKelvin",ModConfigs.server().nativeWireHeatCapacityJoulesPerKelvinMetre.getF()*length,
            "conductanceWattsPerKelvin",ModConfigs.server().nativeWireCoolingWattsPerKelvinMetre.getF()*length,
            "ambientKelvin",293.15,"tripKelvin",(double)ModConfigs.server().nativeWireTripKelvin.getF());}
}
