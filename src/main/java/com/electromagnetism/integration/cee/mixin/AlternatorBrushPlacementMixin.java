package com.electromagnetism.integration.cee.mixin;

import com.george_vi.electroenergetics.content.rotor.AlternatorBrushesBlock;
import com.george_vi.electroenergetics.content.rotor.ThreePhaseAlternatorBrushesBlock;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = {AlternatorBrushesBlock.class, ThreePhaseAlternatorBrushesBlock.class}, remap = false)
public abstract class AlternatorBrushPlacementMixin {
    @Unique
    private static final Direction[] electromagnetism$noDirections = new Direction[0];

    // Both brushes already compute the motor's preferred facing before scanning for adjacent rotors.
    // Single-phase brushes scan during placement, three-phase brushes scan in getPreferredFacing.
    @Redirect(method = {"getStateForPlacement", "getPreferredFacing"},
            at = @At(value = "FIELD", target = "Lnet/createmod/catnip/data/Iterate;directions:[Lnet/minecraft/core/Direction;"),
            require = 1, allow = 1)
    private Direction[] electromagnetism$skipRotorFacingOverride() {
        return electromagnetism$noDirections;
    }
}
