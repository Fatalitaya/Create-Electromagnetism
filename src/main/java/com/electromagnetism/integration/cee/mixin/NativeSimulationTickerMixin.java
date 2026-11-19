package com.electromagnetism.integration.cee.mixin;

import com.electromagnetism.integration.cee.nativebridge.CeeSimulationAccess;
import com.electromagnetism.integration.cee.nativebridge.CeeInputInventory;
import com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData;
import com.electromagnetism.simulation.NativeSimulation;
import com.electromagnetism.simulation.SimulationProtocol.Diagnostic;
import com.george_vi.electroenergetics.simulation.simulator.SimulationTicker;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Own both boundaries. Never enter preTick, legacy worker, postTick, or legacy finish consumers.
 * MinecraftServerMixin still calls DevicesSavedData.tick separately, preserving initialization.
 */
@Mixin(value = SimulationTicker.class, remap = false)
public abstract class NativeSimulationTickerMixin implements CeeSimulationAccess {
    @Shadow @Final public InfrastructureSavedData sd;
    @Override public boolean electromagnetism$exchangeEnergy(java.util.UUID branch,double joules,double maximumJoules,boolean commit){return electromagnetism$world().exchangeEnergy(branch,joules,maximumJoules,commit);}
    @Unique private com.electromagnetism.integration.cee.nativebridge.CeeNativeWorld electromagnetism$world;
    @Unique private Diagnostic electromagnetism$reported;
    @Unique private com.electromagnetism.integration.cee.nativebridge.CeeNativeWorld electromagnetism$world() {
        if(electromagnetism$world==null) electromagnetism$world=new com.electromagnetism.integration.cee.nativebridge.CeeNativeWorld(sd);
        return electromagnetism$world;
    }
    @Override public Diagnostic electromagnetism$nativeStatus() { return electromagnetism$world().status(); }
    @Override public java.util.Optional<com.electromagnetism.simulation.FaultDomainManager.TickReport> electromagnetism$nativeProgress() {return electromagnetism$world().progress();}
    @Override public void electromagnetism$resumeMissingInputs() {electromagnetism$world().resumeMissingInputs();}
    @Override public com.electromagnetism.simulation.SimulationCoordinator.Frame electromagnetism$captureInputs() {
        return electromagnetism$world().capture();
    }
    @Override public CeeInputInventory electromagnetism$inspectInputs() {
        var capture=electromagnetism$captureInputs();return new CeeInputInventory(capture.devices(),capture.blockers());
    }
    @Override public net.minecraft.nbt.CompoundTag electromagnetism$saveNative(){return electromagnetism$world().save();}
    @Override public void electromagnetism$restoreNative(net.minecraft.nbt.CompoundTag state){electromagnetism$world().restore(state);}
    @Override public void electromagnetism$closeNative(){electromagnetism$world().close();}
    @Override public java.util.Optional<com.electromagnetism.simulation.SimulationProtocol.Reading> electromagnetism$reading(java.util.UUID branch){return electromagnetism$world().reading(branch);}
    @Inject(method = "tick()V", at = @At("HEAD"), cancellable = true)
    private void electromagnetism$captureAndSubmit(CallbackInfo ci) {
        ci.cancel();electromagnetism$world().tick();
        electromagnetism$reportStatus();
    }
    @Unique private void electromagnetism$reportStatus() {
        Diagnostic status=electromagnetism$world().status();
        if (!status.equals(electromagnetism$reported)) {
            var logger=LoggerFactory.getLogger("Electromagnetism/Native");
            switch(status.status()) {
                case REJECTED,UNSUPPORTED,UNAVAILABLE -> logger.error("{}: {}",status.status(),status.reason());
                default -> logger.info("{}: {}",status.status(),status.reason());
            }
            electromagnetism$reported=status;
        }
    }
    @Inject(method = "endTick()V", at = @At("HEAD"), cancellable = true)
    private void electromagnetism$acceptAndPublish(CallbackInfo ci) {ci.cancel();electromagnetism$world().endTick();electromagnetism$reportStatus();}
}
