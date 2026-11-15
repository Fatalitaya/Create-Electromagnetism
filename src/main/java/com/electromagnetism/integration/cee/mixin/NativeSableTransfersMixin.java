package com.electromagnetism.integration.cee.mixin;

import com.electromagnetism.integration.cee.nativebridge.*;
import com.george_vi.electroenergetics.devices.device.*;
import com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData;
import it.unimi.dsi.fastutil.longs.*;
import net.createmod.catnip.data.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.*;

/** Replaces only CEE's copying queue; ordinary deferred initialization remains active.
 * Magnetic/electrical/thermal state stays in the dimension's native session under
 * unchanged identities. No Java serialization of old voltages or energy is imported.
 */
@Mixin(value=DevicesSavedData.class,remap=false)
public abstract class NativeSableTransfersMixin implements NativeSableTransfers {
    @Shadow @Final public ServerLevel level;
    @Shadow @Final Long2ObjectMap<SimulatedDevice> DEVICES_BY_POS;
    @Shadow List<SimulatedDevice>[] DEVICES_BY_FEATURE_TYPE;
    @Shadow public Deque<Pair<BlockPos,SimulatedDevice>> devicesToAddSable;
    @Shadow public Deque<SimulatedDevice> devicesToInitialize;
    @Unique private String electromagnetism$problem="";
    @Override public String electromagnetism$transferProblem(){return electromagnetism$problem;}
    @Inject(method="tick",at=@At("HEAD"),cancellable=true)
    private void electromagnetism$transfer(CallbackInfo ci) {
        try {electromagnetism$completeTransfers();}
        catch(IllegalArgumentException | IllegalStateException invalid) {
            electromagnetism$problem=invalid.getMessage();
            // Keep independent initialization, but never execute the copying loop.
            while(!devicesToInitialize.isEmpty())devicesToInitialize.pop().initialize();
            ci.cancel();
        }
    }
    @Override public void electromagnetism$completeTransfers() {
        if(devicesToAddSable.isEmpty())return;
        if(devicesToAddSable.size()>4096)throw new IllegalArgumentException("Sable device transfer exceeds bounded batch");
        var self=(DevicesSavedData)(Object)this;
        var destinations=new HashSet<BlockPos>();var sources=Collections.newSetFromMap(new IdentityHashMap<SimulatedDevice,Boolean>());
        var identities=new HashSet<com.electromagnetism.simulation.SimulationProtocol.DeviceId>();
        var prepared=new ArrayList<SimulatedDevice>();
        // Construct and validate every successor before touching the live maps.
        for(var entry:devicesToAddSable) {
            var source=entry.getSecond();var destination=entry.getFirst();
            if(source.level!=level || !sources.add(source) || !destinations.add(destination))throw new IllegalArgumentException("Duplicate or cross-session Sable transfer");
            var identity=(CeeDeviceIdentity)source;
            if(!identity.electromagnetism$identityProblem().isBlank() || !identities.add(identity.electromagnetism$nativeIdentity()))throw new IllegalArgumentException("Invalid Sable source identity");
            if(!level.getChunkSource().hasChunk(destination.getX()>>4,destination.getZ()>>4))throw new IllegalStateException("Sable destination owner is unloaded: "+destination);
            var existing=self.getDevice(destination);
            if(existing!=null && existing.type!=source.type)throw new IllegalArgumentException("Sable destination has a different device type: "+destination);
            var data=new CompoundTag();source.write(data);
            var successor=source.type.create(level,destination,self);successor.read(data);
            ((CeeDeviceIdentity)successor).electromagnetism$restoreIdentity(identity.electromagnetism$saveIdentity());
            prepared.add(successor);
        }
        var next=new Long2ObjectOpenHashMap<SimulatedDevice>(DEVICES_BY_POS);
        for(var source:sources)if(next.get(source.pos.asLong())==source)next.remove(source.pos.asLong());
        var retired=Collections.newSetFromMap(new IdentityHashMap<SimulatedDevice,Boolean>());retired.addAll(sources);
        for(var successor:prepared){var old=next.put(successor.pos.asLong(),successor);if(old!=null)retired.add(old);}
        // Build feature lists before the single main-thread ownership swap.
        var features=new ArrayList<List<SimulatedDevice>>(DEVICES_BY_FEATURE_TYPE.length);
        for(int n=0;n<DEVICES_BY_FEATURE_TYPE.length;n++)features.add(new ArrayList<>());
        for(var device:next.values())for(var feature:DevicesSavedData.getFeatureTypesOf(device))features.get(feature.id).add(device);
        DEVICES_BY_POS.clear();DEVICES_BY_POS.putAll(next);
        for(int n=0;n<DEVICES_BY_FEATURE_TYPE.length;n++)DEVICES_BY_FEATURE_TYPE[n]=features.get(n);
        devicesToAddSable.clear();devicesToInitialize.removeIf(retired::contains);
        for(var old:retired)((CeeDeviceIdentity)old).electromagnetism$retireTransferredDevice();
        var infrastructure=InfrastructureSavedData.load(level);
        for(var source:sources)if(!destinations.contains(source.pos))infrastructure.removeNodes(source.pos);
        for(var successor:prepared){successor.update();devicesToInitialize.add(successor);}
        electromagnetism$problem="";self.setDirty();infrastructure.setDirty();
    }
}
