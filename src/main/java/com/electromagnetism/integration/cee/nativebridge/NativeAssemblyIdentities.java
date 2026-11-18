package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.WorldRecipients;
import net.minecraft.nbt.*;
import java.util.*;

final class NativeAssemblyIdentities {
    private List<WorldRecipients.Assembly> captured=List.of();

    void restore(List<WorldRecipients.Assembly> saved) {captured=List.copyOf(saved);}

    Map<UUID,WorldRecipients.Assembly> plan(List<List<UUID>> components) {
        var candidates=new HashMap<UUID,WorldRecipients.Assembly>();
        var survivors=new HashSet<UUID>();components.forEach(survivors::addAll);
        for(var members:components) {
            var inherited=captured.stream().filter(old->{
                UUID heir=survivors.contains(old.anchor())?old.anchor():old.members().stream().filter(survivors::contains).min(UUID::compareTo).orElse(null);
                return heir!=null && members.contains(heir);
            }).min(Comparator.comparing(WorldRecipients.Assembly::identity)).orElse(null);
            UUID identity=inherited==null?UUID.randomUUID():inherited.identity();
            UUID anchor=inherited!=null && members.contains(inherited.anchor())?inherited.anchor():members.getFirst();
            var group=new WorldRecipients.Assembly(identity,anchor,members,List.of(),List.of());
            for(UUID member:members)if(candidates.put(member,group)!=null)throw new IllegalArgumentException("Overlapping alternator assemblies");
        }
        return Map.copyOf(candidates);
    }

    void capture(List<WorldRecipients.Assembly> result) {
        var retained=new ArrayList<>(result);
        var currentMembers=result.stream().flatMap(assembly->assembly.members().stream()).collect(java.util.stream.Collectors.toSet());
        for(var old:captured)if(Collections.disjoint(old.members(),currentMembers))retained.add(old);
        if(retained.size()>4096)throw new IllegalArgumentException("Assembly identity capacity exceeded");
        captured=List.copyOf(retained);
    }

    ListTag save() {
        var result=new ListTag();
        for(var assembly:captured) {
            var tag=new CompoundTag();tag.putUUID("Identity",assembly.identity());tag.putUUID("Anchor",assembly.anchor());
            var members=new ListTag();
            for(UUID member:assembly.members()){var value=new CompoundTag();value.putUUID("Identity",member);members.add(value);}
            tag.put("Members",members);result.add(tag);
        }
        return result;
    }

    void restore(ListTag tags) {
        if(tags.size()>4096)throw new IllegalArgumentException("Assembly identity capacity exceeded");
        var restored=new ArrayList<WorldRecipients.Assembly>();var owners=new HashSet<UUID>();var identities=new HashSet<UUID>();
        for(var value:tags) {
            var tag=(CompoundTag)value;var identity=tag.getUUID("Identity");var anchor=tag.getUUID("Anchor");
            var members=new ArrayList<UUID>();
            for(var entry:tag.getList("Members",Tag.TAG_COMPOUND)) {
                UUID member=((CompoundTag)entry).getUUID("Identity");
                if(!owners.add(member))throw new IllegalArgumentException("Duplicate assembly member");
                members.add(member);
            }
            if(!identities.add(identity) || members.isEmpty() || !members.contains(anchor))throw new IllegalArgumentException("Invalid assembly identity");
            restored.add(new WorldRecipients.Assembly(identity,anchor,members,List.of(),List.of()));
        }
        captured=List.copyOf(restored);
    }
}
