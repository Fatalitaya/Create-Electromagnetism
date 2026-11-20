package com.electromagnetism.simulation;

import java.util.*;
import static com.electromagnetism.simulation.SimulationProtocol.*;

/** Position locates the target. Its delivery address also binds the immutable instance identity. */
public record WorldRecipients(Map<DeviceId,Long> positions,Map<UUID,DeviceId> branches,
                              List<Assembly> assemblies,Map<DeviceId,List<DeviceId>> damageGroups,
                              Map<DeviceId,Long> mechanicalNetworks,Map<UUID,UUID> effects,Map<DeviceId,List<String>> blockers,Map<UUID,Wire> wires) {
    public WorldRecipients(Map<DeviceId,Long> positions,Map<UUID,DeviceId> branches,List<Assembly> assemblies,Map<DeviceId,List<DeviceId>> damageGroups,Map<DeviceId,Long> mechanicalNetworks) {
        this(positions,branches,assemblies,damageGroups,mechanicalNetworks,Map.of(),Map.of(),Map.of());
    }
    public record Wire(long firstPosition,int firstTerminal,long secondPosition,int secondTerminal,boolean catenary) {}
    public WorldRecipients(Map<DeviceId,Long> positions,Map<UUID,DeviceId> branches,List<Assembly> assemblies,Map<DeviceId,List<DeviceId>> damageGroups,Map<DeviceId,Long> mechanicalNetworks,Map<UUID,UUID> effects,Map<DeviceId,List<String>> blockers) {
        this(positions,branches,assemblies,damageGroups,mechanicalNetworks,effects,blockers,Map.of());
    }
    public static final WorldRecipients EMPTY=new WorldRecipients(Map.of(),Map.of(),List.of(),Map.of(),Map.of());
    public record Assembly(UUID identity,UUID anchor,List<UUID> members,List<UUID> phases,List<Long> displays) {
        public Assembly {members=List.copyOf(members);phases=List.copyOf(phases);displays=List.copyOf(displays);}
    }
    public WorldRecipients {
        positions=Map.copyOf(positions);branches=Map.copyOf(branches);assemblies=List.copyOf(assemblies);
        var groups=new HashMap<DeviceId,List<DeviceId>>();damageGroups.forEach((id,members)->groups.put(id,List.copyOf(members)));
        damageGroups=Map.copyOf(groups);mechanicalNetworks=Map.copyOf(mechanicalNetworks);effects=Map.copyOf(effects);
        var failures=new HashMap<DeviceId,List<String>>();blockers.forEach((id,reasons)->failures.put(id,List.copyOf(reasons)));blockers=Map.copyOf(failures);wires=Map.copyOf(wires);
    }
    void encode(NativeWireCodec.Writer w) {
        w.integer(positions.size());positions.forEach((id,pos)->{device(w,id);w.longInteger(pos);});
        w.integer(branches.size());branches.forEach((branch,id)->{w.uuid(branch);device(w,id);});
        w.integer(assemblies.size());for(var a:assemblies) {
            w.uuid(a.identity()).uuid(a.anchor()).integer(a.members().size());a.members().forEach(w::uuid);
            w.integer(a.phases().size());a.phases().forEach(w::uuid);
            w.integer(a.displays().size());a.displays().forEach(w::longInteger);
        }
        w.integer(damageGroups.size());damageGroups.forEach((id,group)->{device(w,id);w.integer(group.size());group.forEach(member->device(w,member));});
        w.integer(mechanicalNetworks.size());mechanicalNetworks.forEach((id,network)->{device(w,id);w.longInteger(network);});
        w.integer(wires.size());wires.forEach((id,wire)->w.uuid(id).longInteger(wire.firstPosition()).integer(wire.firstTerminal()).longInteger(wire.secondPosition()).integer(wire.secondTerminal()).integer(wire.catenary()?1:0));
        w.integer(effects.size());effects.forEach((branch,entity)->w.uuid(branch).uuid(entity));
        w.integer(blockers.size());blockers.forEach((id,reasons)->{device(w,id);w.integer(reasons.size());reasons.forEach(w::string);});
    }
    static WorldRecipients decode(NativeWireCodec.Reader r) {
        var positions=new HashMap<DeviceId,Long>();int count=r.count(4096);
        for(int i=0;i<count;i++)if(positions.put(device(r),r.longInteger())!=null)throw new IllegalArgumentException("Duplicate recipient");
        var branches=new HashMap<UUID,DeviceId>();count=r.count(4096);
        for(int i=0;i<count;i++)if(branches.put(r.uuid(),device(r))!=null)throw new IllegalArgumentException("Duplicate branch recipient");
        var assemblies=new ArrayList<Assembly>();count=r.count(4096);
        for(int i=0;i<count;i++) {
            UUID id=r.uuid(),anchor=r.uuid();var members=new ArrayList<UUID>();int n=r.count(4096);for(int j=0;j<n;j++)members.add(r.uuid());
            var phases=new ArrayList<UUID>();n=r.count(4096);for(int j=0;j<n;j++)phases.add(r.uuid());
            var displays=new ArrayList<Long>();n=r.count(4096);for(int j=0;j<n;j++)displays.add(r.longInteger());
            if(members.isEmpty() || !members.contains(anchor))throw new IllegalArgumentException("Invalid assembly anchor");
            assemblies.add(new Assembly(id,anchor,members,phases,displays));
        }
        var groups=new HashMap<DeviceId,List<DeviceId>>();count=r.count(4096);
        for(int i=0;i<count;i++){var id=device(r);var members=new ArrayList<DeviceId>();int n=r.count(4096);for(int j=0;j<n;j++)members.add(device(r));groups.put(id,members);}
        var networks=new HashMap<DeviceId,Long>();count=r.count(4096);for(int i=0;i<count;i++)networks.put(device(r),r.longInteger());
        var wires=new HashMap<UUID,Wire>();count=r.count(4096);for(int i=0;i<count;i++)wires.put(r.uuid(),new Wire(r.longInteger(),r.integer(),r.longInteger(),r.integer(),r.count(1)==1));
        var effects=new HashMap<UUID,UUID>();count=r.count(4096);for(int i=0;i<count;i++)effects.put(r.uuid(),r.uuid());
        var blockers=new HashMap<DeviceId,List<String>>();count=r.count(4096);
        for(int i=0;i<count;i++){var id=device(r);int n=r.count(4096);var reasons=new ArrayList<String>();for(int j=0;j<n;j++)reasons.add(r.string());blockers.put(id,reasons);}
        return new WorldRecipients(positions,branches,assemblies,groups,networks,effects,blockers,wires);
    }
    private static void device(NativeWireCodec.Writer w,DeviceId id){w.uuid(id.instance()).longInteger(id.generation());}
    private static DeviceId device(NativeWireCodec.Reader r){return new DeviceId(r.uuid(),r.longInteger());}
}
