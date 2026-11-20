package com.electromagnetism.integration.cee.mixin;

import com.george_vi.electroenergetics.*;
import com.george_vi.electroenergetics.events.datagen.CEEAdvancements;
import com.george_vi.electroenergetics.content.bundled_wire.*;
import com.george_vi.electroenergetics.simulation.infrastructure.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The second click commits the cable. Its sheath owns the persistent identity, and capture expands
 * its two insulated cores.
 */
@Mixin(value=BundledWireItem.class,remap=false)
public abstract class NativeBundledWireItemMixin {
    @Inject(method="useOn",at=@At("HEAD"),cancellable=true)
    private void electromagnetism$connect(UseOnContext context,CallbackInfoReturnable<InteractionResult> ci) {
        var player=context.getPlayer();var stack=context.getItemInHand();var level=context.getLevel();
        if(player==null || player.isShiftKeyDown())return;
        var selected=stack.get(CEEDataComponents.SELECTED_WIRE_TERMINATION);
        if(selected==null)return;
        var state=level.getBlockState(context.getClickedPos());
        if(!(state.getBlock() instanceof BundledWireTerminationBlock target) || target.wireType!=BundledWireType.DUPLEX)return;
        ci.setReturnValue(InteractionResult.FAIL);
        if(!level.isLoaded(selected.pos()) || selected.pos().equals(context.getClickedPos()))return;
        var source=level.getBlockState(selected.pos());
        if(!(source.getBlock() instanceof BundledWireTerminationBlock origin) || origin.wireType!=target.wireType
                || source.getValue(BundledWireTerminationBlock.ROLL)!=selected.roll() || source.getValue(BundledWireTerminationBlock.FLIP)!=selected.flip())return;
        var a=BundledWireNodeConfigurator.getCableNodeFor(selected.pos(),selected.roll(),selected.flip());
        var b=BundledWireNodeConfigurator.getCableNodeFor(context.getClickedPos(),state.getValue(BundledWireTerminationBlock.ROLL),state.getValue(BundledWireTerminationBlock.FLIP));
        double length=Math.sqrt(a.sableSourcePos(level).distSqr(b.sableSourcePos(level)));
        if(!Double.isFinite(length) || length<=0 || length>CEEWireTypes.DUPLEX.get().getMaxLength())return;
        if(level instanceof ServerLevel server) {
            var sd=InfrastructureSavedData.load(server);
            // Validate all endpoints before committing the world change.
            for(int conductor=0;conductor<=2;conductor++) {
                var first=BundledWireNodeConfigurator.getWireNodeFor(a.sourcePos(),selected.roll(),selected.flip(),conductor);
                var second=BundledWireNodeConfigurator.getWireNodeFor(b.sourcePos(),state.getValue(BundledWireTerminationBlock.ROLL),state.getValue(BundledWireTerminationBlock.FLIP),conductor);
                if(sd.getNodeData(first)==null || sd.getNodeData(second)==null)return;
            }
            if(sd.isConnected(a,b))return;
            sd.connect(a,b,WireData.ofLength(CEEWireTypes.DUPLEX.get(),length));
            stack.remove(CEEDataComponents.SELECTED_WIRE_TERMINATION);
            if(!player.isCreative()) {
                stack.shrink(1);
                player.getInventory().placeItemBackInInventory(CEEItems.EMPTY_SPOOL.asStack());
            }
            CEEAdvancements.CONNECT_WIRES.awardTo(player);
        }
        ci.setReturnValue(InteractionResult.SUCCESS);
    }
}
