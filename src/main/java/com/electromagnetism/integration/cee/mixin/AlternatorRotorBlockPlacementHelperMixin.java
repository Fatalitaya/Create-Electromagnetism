package com.electromagnetism.integration.cee.mixin;

import com.electromagnetism.registry.ModBlocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

import net.minecraft.world.item.ItemStack;

/**
 * Add the excitation stator to the rotor placement helper so it follows the same snapping and roll
 * rules.
 */
@Mixin(targets = "com.george_vi.electroenergetics.content.rotor.AlternatorRotorBlock$PlacementHelper")
public abstract class AlternatorRotorBlockPlacementHelperMixin {
    @Inject(method = "getItemPredicate", at = @At("RETURN"), cancellable = true, remap = false)
    private void electromagnetism$alsoMatchExcitationStator(CallbackInfoReturnable<Predicate<ItemStack>> cir) {
        Predicate<ItemStack> original = cir.getReturnValue();
        cir.setReturnValue(stack -> original.test(stack)
                || stack.getItem() == ModBlocks.EXCITATION_STATOR.get().asItem());
    }
}
