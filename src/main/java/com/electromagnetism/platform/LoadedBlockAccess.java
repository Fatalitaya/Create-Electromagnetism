package com.electromagnetism.platform;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Read loaded blocks on the world thread without creating or extending chunk tickets. */
public final class LoadedBlockAccess {
    private LoadedBlockAccess() {}
    public static BlockEntity entity(Level level,BlockPos pos) {
        var chunk=level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
        return chunk==null?null:chunk.getBlockEntity(pos);
    }
    public static BlockState state(Level level,BlockPos pos) {
        var chunk=level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
        return chunk==null?Blocks.AIR.defaultBlockState():chunk.getBlockState(pos);
    }
}
