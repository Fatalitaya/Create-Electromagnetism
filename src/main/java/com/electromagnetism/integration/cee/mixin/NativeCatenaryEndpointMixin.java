package com.electromagnetism.integration.cee.mixin;
import com.electromagnetism.integration.cee.nativebridge.NativeCatenaryEndpoint;
import com.george_vi.electroenergetics.content.railway_electrification.catenary.CatenaryHolderBlockEntity;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.UUID;
@Mixin(value=CatenaryHolderBlockEntity.class,remap=false)
public abstract class NativeCatenaryEndpointMixin implements NativeCatenaryEndpoint {
    @Unique private UUID electromagnetism$id=UUID.randomUUID();
    @Unique private Tag electromagnetism$invalid;
    @Override public UUID electromagnetism$endpointId(){return electromagnetism$id;}
    @Override public String electromagnetism$endpointProblem(){return electromagnetism$invalid==null?"":"Malformed catenary holder identity";}
    @Inject(method="read",at=@At("TAIL"))
    private void electromagnetism$read(CompoundTag tag,HolderLookup.Provider registry,boolean packet,CallbackInfo ci){if(tag.hasUUID("NativeEndpoint"))electromagnetism$id=tag.getUUID("NativeEndpoint");else if(tag.contains("NativeEndpoint"))electromagnetism$invalid=tag.get("NativeEndpoint").copy();}
    @Inject(method="write",at=@At("TAIL"))
    private void electromagnetism$write(CompoundTag tag,HolderLookup.Provider registry,boolean packet,CallbackInfo ci){if(electromagnetism$invalid!=null)tag.put("NativeEndpoint",electromagnetism$invalid.copy());else tag.putUUID("NativeEndpoint",electromagnetism$id);}
}
