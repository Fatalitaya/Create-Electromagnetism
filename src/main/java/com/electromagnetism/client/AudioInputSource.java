package com.electromagnetism.client;
import net.minecraft.core.BlockPos;
/** Capture game audio at a world position through a common input-source interface. */
public interface AudioInputSource {
    short[] capture(BlockPos microphone,int samples,int sampleRate);
    default void close(){}
}
