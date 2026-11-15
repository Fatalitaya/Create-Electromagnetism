package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.config.ModConfigs;
import com.electromagnetism.simulation.SimulationProtocol.*;
import com.george_vi.electroenergetics.CEEWireTypes;
import com.george_vi.electroenergetics.foundation.nodes.*;
import com.george_vi.electroenergetics.simulation.infrastructure.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Read-only geometry adapter. Rust owns currents and one thermal state per conductor. */
public final class NativeConductors {
    private NativeConductors(){}
    public record Capture(Map<Node,PortId> taps,Set<UUID> catenary){}
    public static Capture capture(InfrastructureSavedData sd,Map<InWorldNode,PortId> ports,Set<DeviceId> identities,List<DeviceInput> devices,List<Branch> branches,Map<UUID,InWorldNodeConnection> owners,Map<Node,UUID> stableTaps){
        var taps=new HashMap<Node,PortId>();var catenary=new HashSet<UUID>();
        for(var entry:sd.wireSimulationState.getAllConnections()) {
            var connection=entry.getKey();var geometry=entry.getValue();var wire=geometry.wireData;
            if(!(wire instanceof CeeWireIdentity identity) || !identity.electromagnetism$wireIdentityProblem().isEmpty())throw new IllegalArgumentException("Conductor identity unavailable");
            UUID id=identity.electromagnetism$wireIdentity();
            NativeWireAttachments.validate(wire);
            // The duplex sheath is decorative, but owns two electrical strands.
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
                    UUID strand=UUID.nameUUIDFromBytes((id+"/conductor/"+conductor).getBytes(StandardCharsets.UTF_8));
                    branches.add(new Branch(strand,first,second,"thermal_fuse",thermal(CEEWireTypes.BUNDLE_CONDUCTOR.get().getResistance()*length,length)));
                    if(owners.put(strand,connection)!=null)throw new IllegalArgumentException("Duplicate conductor identity");
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
            if(owners.put(id,connection)!=null)throw new IllegalArgumentException("Duplicate conductor identity");
            if(geometry.isCatenary)catenary.add(id);
        }
        var cross=new ArrayList<it.unimi.dsi.fastutil.objects.ObjectDoublePair<DirectionalNodeConnection>>();
        sd.wireCrossContactModule.loadLazyConnections(cross);
        for(var entry:cross) {
            var connection=entry.left();
            var first=connection.node1();var second=connection.node2();
            var a=first instanceof InWorldNode node?ports.get(node):taps.get(first);
            var b=second instanceof InWorldNode node?ports.get(node):taps.get(second);
            if(a==null||b==null)throw new IllegalArgumentException("Unresolved crossing contact");
            double ohms=entry.rightDouble();if(!Double.isFinite(ohms)||ohms<=0)throw new IllegalArgumentException("Invalid crossing contact resistance");
            String one=a.toString(),two=b.toString();String key=one.compareTo(two)<0?one+"/"+two:two+"/"+one;
            UUID identity=UUID.nameUUIDFromBytes(("cross-contact/"+key).getBytes(StandardCharsets.UTF_8));
            branches.add(new Branch(identity,a,b,"resistor",Map.of("resistanceOhms",ohms)));
        }
        return new Capture(Map.copyOf(taps),Set.copyOf(catenary));
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
