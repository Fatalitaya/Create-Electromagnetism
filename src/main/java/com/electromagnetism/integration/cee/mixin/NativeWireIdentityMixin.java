package com.electromagnetism.integration.cee.mixin;
import com.electromagnetism.integration.cee.nativebridge.CeeWireIdentity;
import com.george_vi.electroenergetics.simulation.infrastructure.WireData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import java.util.UUID;
@Mixin(value=WireData.class,remap=false)
public abstract class NativeWireIdentityMixin implements CeeWireIdentity {
    @Unique private UUID electromagnetism$wireId=UUID.randomUUID();
    @Unique private String electromagnetism$wireProblem="";
    @Override public UUID electromagnetism$wireIdentity(){return electromagnetism$wireId;}
    @Override public void electromagnetism$restoreWireIdentity(UUID identity){electromagnetism$wireId=java.util.Objects.requireNonNull(identity);}
    @Override public String electromagnetism$wireIdentityProblem(){return electromagnetism$wireProblem;}
    @Override public void electromagnetism$rejectWireIdentity(String reason){
        if(electromagnetism$wireProblem.isEmpty())electromagnetism$wireProblem=java.util.Objects.requireNonNull(reason);
    }
}
