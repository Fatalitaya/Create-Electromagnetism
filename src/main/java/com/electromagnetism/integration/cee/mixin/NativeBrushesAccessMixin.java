package com.electromagnetism.integration.cee.mixin;

import com.electromagnetism.integration.cee.nativebridge.CeeMechanicalSource;
import com.george_vi.electroenergetics.content.rotor.AlternatorBrushesBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.foundation.utility.CreateLang;
import net.minecraft.ChatFormatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.List;

/**
 * Supply raw brush speed to the native model and suppress legacy callbacks that would recompute
 * voltage and stress.
 */
@Mixin(value = AlternatorBrushesBlockEntity.class, remap = false)
public abstract class NativeBrushesAccessMixin extends KineticBlockEntity implements CeeMechanicalSource,com.electromagnetism.integration.cee.nativebridge.NativeBrushAssembly {
    @org.spongepowered.asm.mixin.Shadow java.util.List<com.george_vi.electroenergetics.content.rotor.AlternatorRotorBlockEntity> rotors;
    @org.spongepowered.asm.mixin.Shadow BlockPos otherBrush;
    @org.spongepowered.asm.mixin.Unique private String electromagnetism$problem="";
    @Override public String electromagnetism$assemblyProblem(){return electromagnetism$problem;}
    @Override public void electromagnetism$refreshAssembly(){
        var found=new java.util.ArrayList<com.george_vi.electroenergetics.content.rotor.AlternatorRotorBlockEntity>();
        electromagnetism$problem="";otherBrush=null;
        var direction=getBlockState().getValue(com.george_vi.electroenergetics.content.rotor.AlternatorBrushesBlock.FACING).getOpposite();
        for(int offset=1;offset<=16;offset++) {
            var position=getBlockPos().relative(direction,offset);
            if(!level.getChunkSource().hasChunk(position.getX()>>4,position.getZ()>>4)){electromagnetism$problem="Alternator assembly crosses unloaded chunk";break;}
            var be=level.getBlockEntity(position);
            if(be instanceof AlternatorBrushesBlockEntity && be.getBlockState().getValue(com.george_vi.electroenergetics.content.rotor.AlternatorBrushesBlock.FACING)==direction){otherBrush=position;break;}
            if(!(be instanceof com.george_vi.electroenergetics.content.rotor.AlternatorRotorBlockEntity rotor)
                    || be.getBlockState().getValue(com.george_vi.electroenergetics.content.rotor.AlternatorRotorBlock.AXIS)!=direction.getAxis())break;
            found.add(rotor);
        }
        rotors=found;
    }
    @Override public java.util.List<com.george_vi.electroenergetics.content.rotor.AlternatorRotorBlockEntity> electromagnetism$rotors(){return java.util.List.copyOf(rotors);}
    @Override public BlockPos electromagnetism$otherBrush(){return otherBrush;}
    protected NativeBrushesAccessMixin(BlockEntityType<?> type, BlockPos pos, BlockState state) { super(type, pos, state); }
    @Override public float electromagnetism$shaftSpeedRpm() { return ((KineticBlockEntity)(Object)this).getSpeed(); }
    @Inject(method = "addToGoggleTooltip", at = @At("HEAD"), cancellable = true)
    private void electromagnetism$stressTooltip(List<Component> tooltip, boolean sneak, CallbackInfoReturnable<Boolean> ci) {
        if (!IRotate.StressImpact.isEnabled() || rotors.isEmpty()) {
            ci.setReturnValue(false);
            return;
        }
        double stress = 0;
        for (var rotor : rotors)
            stress += rotor.calculateStressApplied() * Math.abs(rotor.getTheoreticalSpeed());
        CreateLang.translate("gui.goggles.kinetic_stats").forGoggles(tooltip);
        CreateLang.translate("tooltip.stressImpact").style(ChatFormatting.GRAY).forGoggles(tooltip);
        CreateLang.number(Math.ceil(stress)).translate("generic.unit.stress")
                .style(ChatFormatting.AQUA).forGoggles(tooltip, 1);
        ci.setReturnValue(true);
    }
    @Inject(method = "tick()V", at = @At("HEAD"), cancellable = true)
    private void electromagnetism$holdElectricalBudget(CallbackInfo ci) {
        ci.cancel();
        super.tick(); // Keep Create's kinetic lifecycle while skipping CEE's electrical budget update.
        electromagnetism$refreshAssembly();
    }
}
