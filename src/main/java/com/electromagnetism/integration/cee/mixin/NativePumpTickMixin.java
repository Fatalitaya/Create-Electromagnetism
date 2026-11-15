package com.electromagnetism.integration.cee.mixin;
import com.electromagnetism.integration.cee.nativebridge.NativePumpBoundary;
import com.simibubi.create.content.fluids.pump.PumpBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=PumpBlockEntity.class,remap=false)
public abstract class NativePumpTickMixin extends KineticBlockEntity {
    protected NativePumpTickMixin(BlockEntityType<?> type,BlockPos pos,BlockState state){super(type,pos,state);}
    @Inject(method="tick()V",at=@At("HEAD"),cancellable=true)
    private void electromagnetism$tick(CallbackInfo ci){if((Object)this instanceof NativePumpBoundary){ci.cancel();super.tick();}}
}
