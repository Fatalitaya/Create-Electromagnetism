package com.electromagnetism.integration.cee.mixin;
import com.electromagnetism.integration.cee.nativebridge.*;
import com.george_vi.electroenergetics.foundation.nodes.*;
import com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Save wire identities and the native checkpoint at the same infrastructure save boundary. */
@Mixin(value=InfrastructureSavedData.class,remap=false)
public abstract class NativeInfrastructurePersistenceMixin {
    @org.spongepowered.asm.mixin.Unique private CompoundTag electromagnetism$frozen;
    @Inject(method="save",at=@At("HEAD"))
    private void electromagnetism$freeze(CompoundTag tag,HolderLookup.Provider provider,CallbackInfoReturnable<CompoundTag> cir) {
        electromagnetism$frozen=((CeeSimulationAccess)((InfrastructureSavedData)(Object)this).ticker).electromagnetism$saveNative();
    }
    @Inject(method="save",at=@At("RETURN"))
    private void electromagnetism$save(CompoundTag tag,HolderLookup.Provider provider,CallbackInfoReturnable<CompoundTag> cir) {
        var sd=(InfrastructureSavedData)(Object)this;var result=cir.getReturnValue();
        wireIdentities(sd,result,false);
        CompoundTag state=electromagnetism$frozen;
        if (state!=null) result.put("ElectromagnetismCircuit",state);
    }
    @Inject(method="load(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/nbt/CompoundTag;)Lcom/george_vi/electroenergetics/simulation/infrastructure/InfrastructureSavedData;",at=@At("RETURN"))
    private static void electromagnetism$load(ServerLevel level,CompoundTag tag,CallbackInfoReturnable<InfrastructureSavedData> cir) {
        var sd=cir.getReturnValue();wireIdentities(sd,tag,true);
        if(tag.contains("ElectromagnetismCircuit",Tag.TAG_COMPOUND))
            ((CeeSimulationAccess)sd.ticker).electromagnetism$restoreNative(tag.getCompound("ElectromagnetismCircuit"));
    }
    @Inject(method="setConnectionData(Lcom/george_vi/electroenergetics/foundation/nodes/InWorldNodeConnection;Lcom/george_vi/electroenergetics/simulation/infrastructure/WireData;)V",at=@At("HEAD"))
    private void electromagnetism$editWire(InWorldNodeConnection connection,com.george_vi.electroenergetics.simulation.infrastructure.WireData data,org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        var sd=(InfrastructureSavedData)(Object)this;
        if(sd.getConnectionData(connection) instanceof CeeWireIdentity old && data instanceof CeeWireIdentity replacement) {
            replacement.electromagnetism$restoreWireIdentity(old.electromagnetism$wireIdentity());
            if(!old.electromagnetism$wireIdentityProblem().isEmpty())replacement.electromagnetism$rejectWireIdentity(old.electromagnetism$wireIdentityProblem());
        }
        sd.setDirty();
    }
    @Inject(method="setConnectionData(JLcom/george_vi/electroenergetics/simulation/infrastructure/WireData;)V",at=@At("HEAD"),cancellable=true)
    private void electromagnetism$editPackedWire(long connection,com.george_vi.electroenergetics.simulation.infrastructure.WireData data,org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        var sd=(InfrastructureSavedData)(Object)this;
        sd.setConnectionData(sd.resolveConnection(connection),data);ci.cancel();
    }
    private static void wireIdentities(InfrastructureSavedData sd,CompoundTag tag,boolean read) {
        var restored=new java.util.IdentityHashMap<CeeWireIdentity,java.util.UUID>();
        boolean ownsPhysics=tag.contains("ElectromagnetismCircuit");
        var catenaries=((NativeCatenaryDataAccess)sd).electromagnetism$catenaryData();
        for(Tag item:tag.getList("Catenary",Tag.TAG_COMPOUND)) {
            var envelope=(CompoundTag)item;int[] from=envelope.getIntArray("From");if(from.length!=3)continue;
            var position=new net.minecraft.core.BlockPos(from[0],from[1],from[2]);
            for(Tag other:envelope.getList("Connections",Tag.TAG_COMPOUND)) {
                var entry=(CompoundTag)other;int[] to=entry.getIntArray("Pos");if(to.length!=3)continue;
                var data=catenaries.get(new com.george_vi.electroenergetics.content.railway_electrification.catenary.CatenaryConnection(position,new net.minecraft.core.BlockPos(to[0],to[1],to[2])));
                if(!(data instanceof CeeWireIdentity identity))continue;
                if(read) {
                    if(entry.hasUUID("NativeConductor")) {
                        var id=entry.getUUID("NativeConductor");var previous=restored.putIfAbsent(identity,id);
                        if(previous!=null&&!previous.equals(id))identity.electromagnetism$rejectWireIdentity("Conflicting catenary endpoint identities");else identity.electromagnetism$restoreWireIdentity(id);
                    } else if(ownsPhysics||entry.contains("NativeConductor"))identity.electromagnetism$rejectWireIdentity("Missing or malformed catenary identity");
                    if(entry.contains("NativeConductorProblem"))identity.electromagnetism$rejectWireIdentity(entry.getString("NativeConductorProblem"));
                } else {
                    entry.putUUID("NativeConductor",identity.electromagnetism$wireIdentity());
                    if(!identity.electromagnetism$wireIdentityProblem().isEmpty())entry.putString("NativeConductorProblem",identity.electromagnetism$wireIdentityProblem());
                }
            }
        }
        for(Tag item:tag.getList("Nodes",Tag.TAG_COMPOUND)) {
            var node=(CompoundTag)item;var from=InWorldNode.readFromTag(node);
            for(Tag other:node.getList("ConnectedNodes",Tag.TAG_COMPOUND)) {
                var entry=(CompoundTag)other;var connection=new InWorldNodeConnection(from,InWorldNode.readFromTag(entry));
                var data=sd.getConnectionData(connection);
                if (data instanceof CeeWireIdentity identity) {
                    if (read) {
                        if(entry.contains("ElectromagnetismIdentityProblem"))
                            identity.electromagnetism$rejectWireIdentity("Persisted invalid wire identity: "+entry.getString("ElectromagnetismIdentityProblem"));
                        if(entry.hasUUID("ElectromagnetismIdentity")) {
                            var id=entry.getUUID("ElectromagnetismIdentity");
                            var previous=restored.putIfAbsent(identity,id);
                            if(previous!=null && !previous.equals(id))
                                identity.electromagnetism$rejectWireIdentity("Conflicting endpoint identities for "+connection);
                            else identity.electromagnetism$restoreWireIdentity(id);
                        } else if(ownsPhysics || entry.contains("ElectromagnetismIdentity"))
                            identity.electromagnetism$rejectWireIdentity("Missing or malformed wire identity for "+connection);
                    } else {
                        entry.putUUID("ElectromagnetismIdentity",identity.electromagnetism$wireIdentity());
                        if(!identity.electromagnetism$wireIdentityProblem().isEmpty())
                            entry.putString("ElectromagnetismIdentityProblem",identity.electromagnetism$wireIdentityProblem());
                    }
                }
            }
        }
    }
}
