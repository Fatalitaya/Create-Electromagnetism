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
        var members=new ArrayList<BlockPos>();members.add(origin);
        if(level.isLoaded(partner) && level.getBlockState(partner).getBlock() instanceof TransformerCoreBlock
                && partner.relative(level.getBlockState(partner).getValue(TransformerCoreBlock.FACING)).equals(origin))members.add(partner);
        var seen=new HashSet<BlockPos>();var pending=new ArrayDeque<BlockPos>();
        seen.addAll(members);pending.addAll(members);int fins=0;
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
        for(var pos:members)if(level.getBlockState(pos).getValue(TransformerCoreBlock.LOGGED_STATE)!=com.george_vi.electroenergetics.foundation.ProperOilAndWaterloggedBlock.LoggedState.DRY)wet++;
        return config.nativeTransformerCoolingWattsPerKelvin.getF()+(double)fins/members.size()*config.nativeTransformerFinCoolingWattsPerKelvin.getF()+(double)wet/members.size()*config.nativeTransformerLiquidCoolingWattsPerKelvin.getF();
    }
}
