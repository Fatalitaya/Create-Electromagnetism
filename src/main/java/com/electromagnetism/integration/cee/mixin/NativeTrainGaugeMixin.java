package com.electromagnetism.integration.cee.mixin;

import com.george_vi.electroenergetics.content.gauge.ElectricGaugeMovementBehaviour;
import com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A missing accepted train sample has no dial, rather than a valid zero reading. */
@Mixin(value=ElectricGaugeMovementBehaviour.class,remap=false)
public abstract class NativeTrainGaugeMixin {
    @Shadow @Final private boolean voltmeter;
    @Inject(method="tick",at=@At("HEAD"),cancellable=true)
    private void electromagnetism$tick(MovementContext context,CallbackInfo ci){
        ci.cancel();double value=Double.NaN;
        if(context.contraption.entity instanceof CarriageContraptionEntity entity){
            var carriage=entity.getCarriage();
            if(carriage!=null&&carriage.train!=null){
                if(context.world.isClientSide()){
                    var values=com.george_vi.electroenergetics.content.railway_electrification.gauges.ClientTrainGaugeData.get(carriage.train.id);
                    if(values!=com.george_vi.electroenergetics.content.railway_electrification.gauges.ClientTrainGaugeData.TrainGaugeValues.ZERO)value=voltmeter?values.voltage():values.current();
                } else {
                    var values=((ICEETrainExtension)carriage.train).getElectricTrainData();value=voltmeter?values.lastVoltage:values.displayCurrent;
                }
            }
        }
        boolean available=Double.isFinite(value);context.data.putBoolean("NativeAvailable",available);
        // Engineering display references: 3 kV bank, 5 ohm armature (600 A stall).
        float target=available?(float)Math.clamp(Math.abs(value)/(voltmeter?3000:600),0,1):0;
        float previous=context.data.contains("Initialized")?context.data.getFloat("DialState"):target;
        if(!Float.isFinite(previous))previous=target;
        context.data.putFloat("PrevDialState",previous);context.data.putFloat("DialState",previous+(target-previous)*0.25f);
        context.data.putFloat("DialTarget",target);context.data.putBoolean("Initialized",true);
    }
    @Inject(method="renderInContraption",at=@At("HEAD"),cancellable=true)
    private void electromagnetism$missing(MovementContext context,com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld world,com.simibubi.create.content.contraptions.render.ContraptionMatrices matrices,net.minecraft.client.renderer.MultiBufferSource buffer,CallbackInfo ci){if(!context.data.getBoolean("NativeAvailable"))ci.cancel();}
}
