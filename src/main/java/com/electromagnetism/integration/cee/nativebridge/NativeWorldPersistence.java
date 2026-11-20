package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.FaultDomainManager;
import net.minecraft.nbt.*;
import net.neoforged.fml.ModList;
import java.util.*;
import static com.electromagnetism.simulation.SimulationProtocol.*;

final class NativeWorldPersistence {
    private NativeWorldPersistence() {}
    static void requireMetadata(CompoundTag tag) {
        String metadata=ModList.get().getModContainerById("electromagnetism").orElseThrow().getModInfo().getVersion().toString();
        if(!metadata.equals(tag.getString("ModMetadata")))
            throw new IllegalArgumentException("Saved mod metadata differs from installed mod; original state retained");
    }
    static void restoreDomains(CompoundTag retained,String metadata,FaultDomainManager coordinator,
                               ListTag quarantinedDomains,Set<UUID> quarantinedSessions) {
        NativeWorldPersistence.requireMetadata(retained);
        var records=retained.getList("Domains",Tag.TAG_COMPOUND);
        if(records.isEmpty())throw new IllegalArgumentException("Invalid saved fault domains");
        var sessionCounts=new HashMap<UUID,Integer>();
        var memberCounts=new HashMap<DeviceId,Integer>();
        for(var value:records) {
            var record=(CompoundTag)value;
            if(record.hasUUID("Session"))sessionCounts.merge(record.getUUID("Session"),1,Integer::sum);
            try {
                var members=NativeWorldPersistence.members(record);
                if(members!=null)members.forEach(id->memberCounts.merge(id,1,Integer::sum));
            } catch(RuntimeException invalid) {   }
        }
        for(var value:records) {
            var record=(CompoundTag)value;
            Set<DeviceId> members=null;
            try {
                members=NativeWorldPersistence.members(record);
                if(record.hasUUID("Session") && sessionCounts.get(record.getUUID("Session"))==1
                        && (members==null || members.stream().noneMatch(id->memberCounts.get(id)>1))) {
                    coordinator.restore(NativeWorldPersistence.decodeDomain(record,metadata,members));
                    continue;
                }
                coordinator.quarantine(members,"Ambiguous saved electrical domain ownership");
            } catch(RuntimeException invalid) {
                coordinator.quarantine(members,"Electrical domain restore failed: "+invalid.getMessage());
            }
            quarantinedDomains.add(record.copy());
            if(record.hasUUID("Session"))quarantinedSessions.add(record.getUUID("Session"));
        }
    }

    private static Set<DeviceId> members(CompoundTag domain) {
        if(!(domain.get("Members") instanceof ListTag list) || (!list.isEmpty() && list.getElementType()!=Tag.TAG_COMPOUND))return null;
        var members=new HashSet<DeviceId>();
        for(var member:domain.getList("Members",Tag.TAG_COMPOUND)) {
            var id=(CompoundTag)member;
            if(!members.add(new DeviceId(id.getUUID("Instance"),id.getLong("Generation"))))
                throw new IllegalArgumentException("Duplicate saved fault-domain member");
        }
        return members;
    }

    static FaultDomainManager.SavedDomain decodeDomain(CompoundTag domain,String metadata,Set<DeviceId> members) {
        var revision=new Revision(domain.getUUID("Session"),domain.getLong("Topology"),domain.getLong("Parameters"),domain.getLong("Sequence"));
        var payload=domain.getByteArray("State");
        double clock=com.electromagnetism.simulation.JniSimulationBackend.snapshotClock(payload,metadata,revision);
        if(domain.contains("AcceptedTime") && domain.getDouble("AcceptedTime")!=clock)
            throw new IllegalArgumentException("Inconsistent saved physical clock");
        boolean boundary=domain.contains("TickBoundary",Tag.TAG_BYTE_ARRAY);
        return new FaultDomainManager.SavedDomain(new Snapshot(revision,metadata,clock,payload),
                domain.getByteArray(boundary?"TickBoundary":"InputHistory"),boundary,members);
    }
    static CompoundTag encodeDomains(FaultDomainManager.Saved saved) {
        var tag=new CompoundTag();var domains=new ListTag();
        for(var savedDomain:saved.domains()) {
            var snapshot=savedDomain.snapshot();var revision=snapshot.revision();var domain=new CompoundTag();
            domain.putUUID("Session",revision.session());domain.putLong("Topology",revision.topology());domain.putLong("Parameters",revision.parameters());domain.putLong("Sequence",revision.sequence());
            if(savedDomain.members()!=null) {
                var members=new ListTag();
                for(var member:savedDomain.members()) {
                    var id=new CompoundTag();
                    id.putUUID("Instance",member.instance());id.putLong("Generation",member.generation());members.add(id);
                }
                domain.put("Members",members);
            }
            domain.putByteArray("State",snapshot.payload());domain.putByteArray(savedDomain.tickBoundary()?"TickBoundary":"InputHistory",savedDomain.history());domains.add(domain);
        }
        tag.putString("ModMetadata",saved.domains().getFirst().snapshot().modVersion());tag.put("Domains",domains);
        return tag;
    }
    static CompoundTag encode(FaultDomainManager.Saved saved,CompoundTag environment,ListTag assemblies,ListTag events) {
        var tag=encodeDomains(saved);
        tag.put("Environment",environment.copy());
        tag.put("AssemblyIdentities",assemblies.copy());
        tag.put("ConfirmedEvents",events.copy());
        return tag;
    }
}
