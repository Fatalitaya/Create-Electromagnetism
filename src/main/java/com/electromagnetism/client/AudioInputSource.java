package com.electromagnetism.client;
import net.minecraft.core.BlockPos;
/** Shared media interface for game audio and future explicitly enabled hardware capture. */
public interface AudioInputSource {
    short[] capture(BlockPos microphone,int samples,int sampleRate);
    default void close(){}
}
