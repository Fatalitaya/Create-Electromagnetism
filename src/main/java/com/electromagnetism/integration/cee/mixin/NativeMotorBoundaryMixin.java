package com.electromagnetism.integration.cee.mixin;

import com.electromagnetism.integration.cee.nativebridge.NativeMotorBoundary;
import com.electromagnetism.config.ModConfigs;
import com.george_vi.electroenergetics.content.electric_motor.ElectricMotorBlockEntity;
import com.george_vi.electroenergetics.foundation.scroll_value.KineticUnlockableScrollValueBehaviour;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.util.List;

@Mixin(value=ElectricMotorBlockEntity.class,remap=false)
public abstract class NativeMotorBoundaryMixin extends GeneratingKineticBlockEntity implements NativeMotorBoundary {
    protected NativeMotorBoundaryMixin(BlockEntityType<?> type,BlockPos pos,BlockState state){super(type,pos,state);}
    @Shadow protected KineticUnlockableScrollValueBehaviour generatedSpeed;
    @Unique private boolean electromagnetism$available;
    @Unique private double electromagnetism$torque,electromagnetism$volts,electromagnetism$watts;
    @Override public float electromagnetism$shaftSpeedRpm(){return isOverStressed()?0:getSpeed();}
    @Override public void electromagnetism$motor(double torque,double volts,double watts,boolean available){
        electromagnetism$torque=torque;electromagnetism$volts=volts;electromagnetism$watts=watts;electromagnetism$available=available;
        reActivateSource=true;sendData();
    }
    @Inject(method="tick()V",at=@At("HEAD"),cancellable=true)
    private void electromagnetism$tick(CallbackInfo ci){ci.cancel();super.tick();}
    @Inject(method="getGeneratedSpeed",at=@At("HEAD"),cancellable=true)
    private void electromagnetism$speed(CallbackInfoReturnable<Float> cir){
        float rpm=0;
        if(electromagnetism$available && Math.abs(electromagnetism$volts)>1e-6 && generatedSpeed!=null) {
            double free=electromagnetism$volts/ModConfigs.server().nativeMotorTorqueConstant.getF()*60/(2*Math.PI);
            rpm=(float)Math.copySign(Math.min(Math.abs(generatedSpeed.getValue()),Math.abs(free)),free);
        }
        cir.setReturnValue(rpm);
    }
    @Inject(method="calculateAddedStressCapacity",at=@At("HEAD"),cancellable=true)
    private void electromagnetism$capacity(CallbackInfoReturnable<Float> cir){
        double sign=Math.signum(getSpeed()==0?electromagnetism$volts:getSpeed());
        float capacity=electromagnetism$available?(float)Math.max(0,electromagnetism$torque*sign/ModConfigs.server().nativeTorquePerBaseStress.getF()):0;
        lastCapacityProvided=capacity;cir.setReturnValue(capacity);
    }
    @Inject(method="addToGoggleTooltip",at=@At("HEAD"),cancellable=true)
    private void electromagnetism$tooltip(List<Component> tooltip,boolean sneak,CallbackInfoReturnable<Boolean> cir){
        if(!electromagnetism$available)cir.setReturnValue(false);
    }
    @Redirect(method="addToGoggleTooltip", at=@At(value="FIELD", ordinal=0,
            target="Lcom/george_vi/electroenergetics/content/electric_motor/ElectricMotorBlockEntity;load:F"))
    private float electromagnetism$loadFraction(ElectricMotorBlockEntity motor) {
        return capacity>0 ? net.minecraft.util.Mth.clamp(stress/capacity,0,1) : 0;
    }
    @ModifyArg(method="addToGoggleTooltip", at=@At(value="INVOKE",
            target="Lcom/george_vi/electroenergetics/foundation/CEELang;formatPower(D)Lnet/createmod/catnip/lang/LangBuilder;"))
    private double electromagnetism$consumption(double ignored) {
        return Math.max(0,electromagnetism$watts);
    }
    @Inject(method="write",at=@At("TAIL"))
    private void electromagnetism$write(CompoundTag tag,HolderLookup.Provider registry,boolean packet,CallbackInfo ci){if(packet){tag.putBoolean("NativeAvailable",electromagnetism$available);tag.putDouble("NativeTorque",electromagnetism$torque);tag.putDouble("NativeVolts",electromagnetism$volts);tag.putDouble("NativeWatts",electromagnetism$watts);}}
    @Inject(method="read",at=@At("TAIL"))
    private void electromagnetism$read(CompoundTag tag,HolderLookup.Provider registry,boolean packet,CallbackInfo ci){if(packet){electromagnetism$available=tag.getBoolean("NativeAvailable");electromagnetism$torque=tag.getDouble("NativeTorque");electromagnetism$volts=tag.getDouble("NativeVolts");electromagnetism$watts=tag.getDouble("NativeWatts");}}
}
