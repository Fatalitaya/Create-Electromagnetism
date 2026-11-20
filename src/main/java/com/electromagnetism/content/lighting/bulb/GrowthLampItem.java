package com.electromagnetism.content.lighting.bulb;

import com.electromagnetism.registry.ModTags;
import com.electromagnetism.config.ModConfigs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** Grant extra random ticks to tagged blocks within reach of the growth lamp. */
public class GrowthLampItem extends LightBulbItem {
    public GrowthLampItem(Properties properties) {
        super(properties);
    }

    /**
     * Clip the affected volume to the mounting direction and sample each tagged block
     * independently.
     */
    public void tickCrops(ServerLevel level, BlockPos origin, Direction facing) {
        int radius = ModConfigs.server().growthLampRadius.get();
        int xMin = -radius, xMax = radius;
        int yMin = -radius, yMax = radius;
        int zMin = -radius, zMax = radius;
        if (facing != null) {
            switch (facing) {
                case EAST -> xMin = 0;
                case WEST -> xMax = 0;
                case UP -> yMin = 0;
                case DOWN -> yMax = 0;
                case SOUTH -> zMin = 0;
                case NORTH -> zMax = 0;
            }
        }

        int chanceValue = ModConfigs.server().growthLampChance.get();
        var random = level.random;
        for (int x = xMin; x <= xMax; x++) {
            for (int y = yMin; y <= yMax; y++) {
                for (int z = zMin; z <= zMax; z++) {
                    if (chanceValue > 1 && random.nextInt(chanceValue) != 0)
                        continue;
                    BlockPos pos = origin.offset(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.is(ModTags.AFFECTED_BY_LAMP))
                        state.randomTick(level, pos, random);
                }
            }
        }
    }
}
