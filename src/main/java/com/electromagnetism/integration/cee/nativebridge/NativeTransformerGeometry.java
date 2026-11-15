package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.config.ModConfigs;
import com.george_vi.electroenergetics.CEETags;
import com.george_vi.electroenergetics.content.transmission_distribution.transformer.TransformerCoreBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import java.util.*;

/** Bounded loaded-world facts only. Each connected fin contributes its configured
 * SI ambient conductance, split between the two winding thermal nodes.
 */
public final class NativeTransformerGeometry {
    private NativeTransformerGeometry() {}
    public static double coolingPerWinding(ServerLevel level,BlockPos origin) {
        var partner=origin.relative(level.getBlockState(origin).getValue(TransformerCoreBlock.FACING));
        var seen=new HashSet<BlockPos>();var pending=new ArrayDeque<BlockPos>();
        seen.add(origin);seen.add(partner);pending.add(origin);pending.add(partner);int fins=0;
        while(!pending.isEmpty()) {
            var current=pending.removeFirst();
            for(var direction:Direction.values()) {
                var next=current.relative(direction);
                if(next.distSqr(origin)>16 || seen.contains(next))continue;
                if(!level.isLoaded(next))throw new IllegalArgumentException("Unloaded transformer cooling boundary at "+next);
                if(level.getBlockState(next).is(CEETags.TRANSFORMER_HEAT_DISSIPATORS)) {seen.add(next);pending.add(next);fins++;}
            }
        }
        var config=ModConfigs.server();
        int wet=0;
        for(var pos:List.of(origin,partner))if(level.getBlockState(pos).getValue(TransformerCoreBlock.LOGGED_STATE)!=com.george_vi.electroenergetics.foundation.ProperOilAndWaterloggedBlock.LoggedState.DRY)wet++;
        return config.nativeTransformerCoolingWattsPerKelvin.getF()+0.5*fins*config.nativeTransformerFinCoolingWattsPerKelvin.getF()+0.5*wet*config.nativeTransformerLiquidCoolingWattsPerKelvin.getF();
    }
}
