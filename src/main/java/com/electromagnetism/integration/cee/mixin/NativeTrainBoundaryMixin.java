package com.electromagnetism.integration.cee.mixin;
import com.electromagnetism.integration.cee.nativebridge.NativeTrainBoundary;
import com.electromagnetism.simulation.AcceptedImpulse;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.content.trains.entity.Train;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(value=Train.class,remap=false)
public abstract class NativeTrainBoundaryMixin implements NativeTrainBoundary {
    @Unique private final AcceptedImpulse electromagnetism$impulse=new AcceptedImpulse();
    @Override public void electromagnetism$traction(java.util.UUID session,double start,double end,double force,long tick){electromagnetism$impulse.accept(session,start,end,force,tick);}
    @Override public void electromagnetism$tractionUnavailable(){electromagnetism$impulse.clear();}
    @Unique private void electromagnetism$expire(){
        var server=net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if(server!=null)electromagnetism$impulse.expire(server.getTickCount());
    }
    @Unique private boolean electromagnetism$isElectric(){
        var train=(Train)(Object)this;
        return com.simibubi.create.Create.RAILWAYS.trains.get(train.id)==train
                && train.carriages.stream().anyMatch(c->((com.george_vi.electroenergetics.mixin_interfaces.IPantographList)c).electroEnergetics$hasElectricMotor());
    }
    @Unique private double electromagnetism$mass(){return 10000.0*Math.max(1,((Train)(Object)this).carriages.size());}
    @Inject(method="acceleration",at=@At("RETURN"),cancellable=true)
    private void electromagnetism$acceleration(CallbackInfoReturnable<Float> cir){
        var train=(Train)(Object)this;
        if(!electromagnetism$isElectric())return;
        electromagnetism$expire();
        // Let Create apply brakes and drag when traction is unavailable.
        if(train.speed!=0 && (train.targetSpeed*train.speed<=0 || Math.abs(train.targetSpeed)<=Math.abs(train.speed)))return;
        double direction=Math.signum(train.targetSpeed==0?train.speed:train.targetSpeed);
        // Spend the accepted integral of force over time. Convert Create speed from blocks/tick
        // using 1 m per block and 20 ticks per second.
        double acceleration=electromagnetism$impulse.available(direction)/electromagnetism$mass()/20;
        cir.setReturnValue((float)Math.min(cir.getReturnValue(),acceleration));
    }
    @WrapMethod(method="approachTargetSpeed")
    private void electromagnetism$consume(float modifier,Operation<Void> original){
        var train=(Train)(Object)this;
        double before=train.speed;
        boolean electric=electromagnetism$isElectric();
        if(electric)electromagnetism$expire();
        original.call(modifier);
        if(!electric)return;
        double direction=Math.signum(train.speed);
        // Braking may bring speed to zero. Acceleration through zero requires impulse in the
        // opposite direction.
        double base=before*train.speed<0?0:before;
        double gain=Math.max(0,Math.abs(train.speed)-Math.abs(base));
        if(gain==0)return;
        double spent=electromagnetism$impulse.consume(direction*gain*electromagnetism$mass()*20);
        // Cap cumulative impulse spent through multipliers or repeated control calls in the same
        // tick.
        train.speed=base+spent/electromagnetism$mass()/20;
    }
}
