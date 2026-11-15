package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.SimulationProtocol.*;
import com.george_vi.electroenergetics.foundation.nodes.InWorldNode;
import com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** World-thread probe selections. Port identities prevent reuse after block replacement. */
public final class NativeVoltageProbes {
    private record Selection(InWorldNode node,PortId port,long expires) {}
    private record Probe(UUID channel,Selection positive,Selection negative) {}
    private final Map<UUID,Selection> references=new HashMap<>();
    private final Map<UUID,Probe> probes=new HashMap<>();
    private final Set<UUID> active=new HashSet<>();
    private final java.util.function.Function<UUID,ServerPlayer> players;
    public NativeVoltageProbes() {this.players=null;}
    public NativeVoltageProbes(java.util.function.Function<UUID,ServerPlayer> players) {this.players=Objects.requireNonNull(players);}
    private ServerPlayer player(InfrastructureSavedData sd,UUID id) {return players==null?sd.level.getServer().getPlayerList().getPlayer(id):players.apply(id);}
    private static void unavailable(ServerPlayer player) {
        player.displayClientMessage(Component.translatable("electromagnetism.measurement.node_voltage_unavailable"),true);
    }
    private static boolean valid(InfrastructureSavedData sd,ServerPlayer player,Selection selection,Map<InWorldNode,PortId> ports) {
        return player!=null && !player.isSpectator() && player.level()==sd.level
                && selection.expires()>=sd.level.getGameTime()
                && selection.port().equals(ports.get(selection.node()))
                && sd.hasNode(selection.node()) && sd.getNodePosition(selection.node())!=null
                && sd.getNodePosition(selection.node()).distanceToSqr(player.position())<=30;
    }
    public void select(InfrastructureSavedData sd,ServerPlayer player,InWorldNode node,Map<InWorldNode,PortId> ports) {
        var port=ports.get(node);
        if(port==null){unavailable(player);return;}
        var selection=new Selection(node,port,sd.level.getGameTime()+40);
        if(!valid(sd,player,selection,ports)){unavailable(player);return;}
        UUID id=player.getUUID();
        if(player.isShiftKeyDown()) {
            if(!references.containsKey(id) && references.size()>=32){unavailable(player);return;}
            references.put(id,new Selection(node,port,sd.level.getGameTime()+1200));probes.remove(id);
            player.displayClientMessage(Component.translatable("electromagnetism.measurement.reference_selected"),true);return;
        }
        var reference=references.get(id);
        if(reference==null || !valid(sd,player,reference,ports)){unavailable(player);return;}
        var previous=probes.get(id);
        UUID channel=previous!=null && previous.positive().port().equals(port) && previous.negative().port().equals(reference.port())
                ?previous.channel():UUID.randomUUID();
        probes.put(id,new Probe(channel,selection,reference));
    }
    public void subscribe(InfrastructureSavedData sd,Map<InWorldNode,PortId> ports,List<Branch> branches,List<WaveformChannel> channels) {
        references.entrySet().removeIf(e->!valid(sd,player(sd,e.getKey()),e.getValue(),ports));
        active.clear();
        if(probes.isEmpty())return;
        var graph=new HashMap<PortId,Set<PortId>>();
        for(var branch:branches) {
            var terminals=new ArrayList<PortId>();terminals.add(branch.positive());terminals.add(branch.negative());terminals.addAll(branch.additionalPorts());
            if(Set.of("coupled_windings","regulated_converter","relay","carrier_mixer","radio_transmitter","field_shaft","field_ac_shaft").contains(branch.model())) {
                link(graph,terminals.get(0),terminals.get(1));link(graph,terminals.get(2),terminals.get(3));continue;
            }
            if(branch.model().equals("wattmeter_contact")) {link(graph,terminals.get(0),terminals.get(2));continue;}
            if(branch.model().equals("battery_contact")) {link(graph,terminals.get(0),terminals.get(1));continue;}
            for(var a:terminals)graph.computeIfAbsent(a,k->new HashSet<>()).addAll(terminals);
        }
        probes.entrySet().removeIf(e->{
            var player=player(sd,e.getKey());var p=e.getValue();
            if(!valid(sd,player,p.positive(),ports)||!valid(sd,player,p.negative(),ports)) {if(player!=null)unavailable(player);return true;}
            var seen=new HashSet<PortId>();var queue=new ArrayDeque<PortId>();queue.add(p.positive().port());
            while(!queue.isEmpty()){var next=queue.remove();if(seen.add(next))queue.addAll(graph.getOrDefault(next,Set.of()));}
            if(!seen.contains(p.negative().port()) || channels.size()>=32){unavailable(player);return false;}
            channels.add(new WaveformChannel(p.channel(),p.positive().port(),p.negative().port()));active.add(p.channel());return false;
        });
    }
    private static void link(Map<PortId,Set<PortId>> graph,PortId a,PortId b) {
        graph.computeIfAbsent(a,k->new HashSet<>()).add(b);graph.computeIfAbsent(b,k->new HashSet<>()).add(a);
    }
    public void unavailable(InfrastructureSavedData sd) {
        for(var id:probes.keySet()){var player=player(sd,id);if(player!=null)unavailable(player);}
    }
    public void publish(InfrastructureSavedData sd,Waveform waveform) {
        for(var entry:probes.entrySet()) {
            var probe=entry.getValue();
            if(!active.contains(probe.channel()) || !probe.positive().port().equals(waveform.positive()) || !probe.negative().port().equals(waveform.negative()))continue;
            var player=player(sd,entry.getKey());if(player==null)continue;
            if(waveform.samples().isEmpty()){unavailable(player);continue;}
            var sample=waveform.samples().getLast();
            player.displayClientMessage(com.electromagnetism.integration.cee.display.MeterReading.automatic(sample.value().orElse(Double.NaN),"V"),true);
        }
    }
}
