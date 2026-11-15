package com.electromagnetism.integration.cee.mixin;
import com.mojang.blaze3d.audio.SoundBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.nio.ByteBuffer;
import javax.sound.sampled.AudioFormat;
@Mixin(SoundBuffer.class)
public abstract class NativeSoundBufferMixin {
    @Inject(method="<init>",at=@At("TAIL"))
    private void electromagnetism$pcm(ByteBuffer data,AudioFormat format,CallbackInfo ci){com.electromagnetism.client.GameAudioCapture.cache((SoundBuffer)(Object)this,data,format);}
}
