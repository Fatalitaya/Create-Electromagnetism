package com.electromagnetism.integration.cee.nativebridge;

import com.george_vi.electroenergetics.content.fuse.fuse_held.FuseHoldable;
import net.minecraft.nbt.CompoundTag;
public interface NativeFuseHolderDisplay {
    void electromagnetism$slots(FuseHoldable first,CompoundTag firstData,FuseHoldable second,CompoundTag secondData);
}
