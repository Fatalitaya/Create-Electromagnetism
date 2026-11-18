package com.electromagnetism.integration.cee.mixin;

import com.electromagnetism.integration.cee.nativebridge.CeeMechanicalSource;
import com.george_vi.electroenergetics.content.rotor.AlternatorRotorBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Unique;

/** Native will supply torque/stress feedback; no Java excitation or mechanical-energy calculation. */
@Mixin(value = AlternatorRotorBlockEntity.class, remap = false)
public abstract class NativeRotorAccessMixin extends KineticBlockEntity implements com.electromagnetism.integration.cee.nativebridge.NativeRotorBoundary {
    @Unique private java.util.UUID electromagnetism$rotorId=java.util.UUID.randomUUID();
    @Unique private double electromagnetism$torque;
    @Unique private boolean electromagnetism$available;
    @Unique private net.minecraft.nbt.Tag electromagnetism$invalidIdentity;
    @Override public String electromagnetism$rotorProblem(){return electromagnetism$invalidIdentity==null?"":"Malformed native rotor identity";}
    @Override public java.util.UUID electromagnetism$rotorIdentity(){return electromagnetism$rotorId;}
    @Override public double electromagnetism$permanentExcitation(){
        int stators=0;
        var state=getBlockState();var axis=state.getValue(com.george_vi.electroenergetics.content.rotor.AlternatorRotorBlock.AXIS);
        for(var direction:net.minecraft.core.Direction.values()) {
            if(direction.getAxis()==axis)continue;
            var position=getBlockPos().relative(direction);
            if(!level.isLoaded(position))throw new IllegalStateException("Rotor excitation owner unloaded");
            var other=level.getBlockState(position);
            if(com.george_vi.electroenergetics.CEEBlocks.STATOR.has(other)
                    && com.george_vi.electroenergetics.content.rotor.StatorBlock.canPowerRotor(position,other,getBlockPos(),state))stators++;
        }
        return stators;
    }
    @Override public void electromagnetism$torque(double torque,boolean available){
        float previousLoad=((KineticBlockEntityAccessor)(Object)this).electromagnetism$getLastStressApplied();
        electromagnetism$torque=torque;electromagnetism$available=available;
        float load=calculateStressApplied();
        if(hasNetwork())getOrCreateNetwork().updateStressFor(this,load);
        if(load!=previousLoad)sendData();
    }
    @Unique private CompoundTag electromagnetism$legacy = new CompoundTag();
    protected NativeRotorAccessMixin(BlockEntityType<?> type, BlockPos pos, BlockState state) { super(type, pos, state); }
    @Override public float electromagnetism$shaftSpeedRpm() { return ((KineticBlockEntity)(Object)this).getSpeed(); }
    @Inject(method = "lazyTick()V", at = @At("HEAD"), cancellable = true)
    private void electromagnetism$holdExcitation(CallbackInfo ci) { ci.cancel(); super.lazyTick(); }
    @Inject(method = "calculateStressApplied()F", at = @At("HEAD"), cancellable = true)
    private void electromagnetism$unavailableLoad(CallbackInfoReturnable<Float> cir) {
        if(level!=null && level.isClientSide) {
            cir.setReturnValue(((KineticBlockEntityAccessor)(Object)this).electromagnetism$getLastStressApplied());
            return;
        }
        float load=electromagnetism$available?(float)Math.max(0,-electromagnetism$torque*Math.signum(getSpeed())/com.electromagnetism.config.ModConfigs.server().nativeTorquePerBaseStress.getF()):0;
        ((KineticBlockEntityAccessor)(Object)this).electromagnetism$setLastStressApplied(load);
        cir.setReturnValue(load);
    }
    @Inject(method = "read", at = @At("TAIL"))
    private void electromagnetism$preserveLegacy(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        if(clientPacket)((KineticBlockEntityAccessor)(Object)this).electromagnetism$setLastStressApplied(tag.getFloat("NativeRotorStress"));
        electromagnetism$legacy = new CompoundTag();
        electromagnetism$invalidIdentity=tag.contains("NativeRotorIdentity")&&!tag.hasUUID("NativeRotorIdentity")?tag.get("NativeRotorIdentity").copy():null;
        if(tag.hasUUID("NativeRotorIdentity"))electromagnetism$rotorId=tag.getUUID("NativeRotorIdentity");
        if (tag.contains("ELECTROMAGNETISM$FractionalMagnets"))
            electromagnetism$legacy.put("ELECTROMAGNETISM$FractionalMagnets", tag.get("ELECTROMAGNETISM$FractionalMagnets").copy());
    }
    @Inject(method = "write", at = @At("TAIL"))
    private void electromagnetism$saveLegacy(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        if(clientPacket)tag.putFloat("NativeRotorStress",((KineticBlockEntityAccessor)(Object)this).electromagnetism$getLastStressApplied());
        tag.merge(electromagnetism$legacy.copy());
        if(electromagnetism$invalidIdentity!=null)tag.put("NativeRotorIdentity",electromagnetism$invalidIdentity.copy());else tag.putUUID("NativeRotorIdentity",electromagnetism$rotorId);
    }
}
