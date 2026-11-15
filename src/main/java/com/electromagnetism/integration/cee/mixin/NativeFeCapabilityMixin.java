package com.electromagnetism.integration.cee.mixin;

import com.electromagnetism.integration.cee.nativebridge.NativeFeBoundary;
import com.electromagnetism.config.ModConfigs;
import com.george_vi.electroenergetics.content.converter.ConverterBlockEntity;
import net.neoforged.neoforge.energy.EnergyStorage;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value=ConverterBlockEntity.ConverterEnergyStorage.class,remap=false)
public abstract class NativeFeCapabilityMixin extends EnergyStorage {
    protected NativeFeCapabilityMixin(){super(0);}
    @Shadow @Final ConverterBlockEntity be;
    @Unique private boolean electromagnetism$busy;
    @Override public int getMaxEnergyStored(){return NativeFeBoundary.capacity();}
    @Inject(method="getEnergyStored",at=@At("HEAD"),cancellable=true)
    private void electromagnetism$stored(CallbackInfoReturnable<Integer> cir){cir.setReturnValue(NativeFeBoundary.transfer(be,Integer.MAX_VALUE,false,true));}
    @Inject(method="canExtract",at=@At("HEAD"),cancellable=true)
    private void electromagnetism$extractable(CallbackInfoReturnable<Boolean> cir){cir.setReturnValue(NativeFeBoundary.direction(be,false));}
    @Inject(method="canReceive",at=@At("HEAD"),cancellable=true)
    private void electromagnetism$receivable(CallbackInfoReturnable<Boolean> cir){cir.setReturnValue(NativeFeBoundary.direction(be,true));}
    @Inject(method="receiveEnergy",at=@At("HEAD"),cancellable=true)
    private void electromagnetism$receive(int amount,boolean simulate,CallbackInfoReturnable<Integer> cir){cir.setReturnValue(electromagnetism$transfer(amount,true,simulate));}
    @Inject(method="extractEnergy",at=@At("HEAD"),cancellable=true)
    private void electromagnetism$extract(int amount,boolean simulate,CallbackInfoReturnable<Integer> cir){cir.setReturnValue(electromagnetism$transfer(amount,false,simulate));}
    @Unique private int electromagnetism$transfer(int amount,boolean receive,boolean simulate){
        if(electromagnetism$busy || !NativeFeBoundary.direction(be,receive))return 0;
        electromagnetism$busy=true;
        try{return NativeFeBoundary.transferLimited(be,amount,receive,simulate);}
        finally{electromagnetism$busy=false;}
    }
}
