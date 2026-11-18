package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.FaultDomainManager;
import net.minecraft.nbt.*;
import net.neoforged.fml.ModList;
import java.util.ArrayList;
import static com.electromagnetism.simulation.SimulationProtocol.*;

final class NativeWorldPersistence {
    private NativeWorldPersistence() {}
    static void requireMetadata(CompoundTag tag) {
        String metadata=ModList.get().getModContainerById("electromagnetism").orElseThrow().getModInfo().getVersion().toString();
        if(!metadata.equals(tag.getString("ModMetadata")))
            throw new IllegalArgumentException("Saved mod metadata differs from installed mod; original state retained");
    }
    static FaultDomainManager.Saved decodeDomains(CompoundTag tag) {
        requireMetadata(tag);
        var domains=new ArrayList<FaultDomainManager.SavedDomain>();
        for(var value:tag.getList("Domains",Tag.TAG_COMPOUND)) {
            var domain=(CompoundTag)value;
            var snapshot=new Snapshot(new Revision(domain.getUUID("Session"),domain.getLong("Topology"),domain.getLong("Parameters"),domain.getLong("Sequence")),
                    tag.getString("ModMetadata"),domain.getDouble("AcceptedTime"),domain.getByteArray("State"));
            domains.add(new FaultDomainManager.SavedDomain(snapshot,domain.getByteArray("InputHistory")));
        }
        if(domains.isEmpty() || domains.size()>64)throw new IllegalArgumentException("Invalid saved fault domains");
        return new FaultDomainManager.Saved(domains);
    }
    static CompoundTag encodeDomains(FaultDomainManager.Saved saved) {
        var tag=new CompoundTag();var domains=new ListTag();
        for(var savedDomain:saved.domains()) {
            var snapshot=savedDomain.snapshot();var revision=snapshot.revision();var domain=new CompoundTag();
            domain.putUUID("Session",revision.session());domain.putLong("Topology",revision.topology());domain.putLong("Parameters",revision.parameters());domain.putLong("Sequence",revision.sequence());
            domain.putDouble("AcceptedTime",snapshot.acceptedTime());domain.putByteArray("State",snapshot.payload());domain.putByteArray("InputHistory",savedDomain.history());domains.add(domain);
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
