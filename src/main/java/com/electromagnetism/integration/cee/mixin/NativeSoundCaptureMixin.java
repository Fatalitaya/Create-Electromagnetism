package com.electromagnetism.integration.cee.mixin;
import com.electromagnetism.client.GameAudioCapture;
import com.mojang.blaze3d.audio.SoundBuffer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.*;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.concurrent.CompletableFuture;
@Mixin(SoundEngine.class)
public abstract class NativeSoundCaptureMixin {
    @Redirect(method="play",at=@At(value="INVOKE",target="Lnet/minecraft/client/resources/sounds/SoundInstance;getStream(Lnet/minecraft/client/sounds/SoundBufferLibrary;Lnet/minecraft/client/resources/sounds/Sound;Z)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<AudioStream> electromagnetism$stream(SoundInstance sound,SoundBufferLibrary library,net.minecraft.client.resources.sounds.Sound entry,boolean loop){return sound.getStream(library,entry,loop).thenApply(stream->GameAudioCapture.stream(sound,stream));}
    @Redirect(method="play",at=@At(value="INVOKE",target="Lnet/minecraft/client/sounds/SoundBufferLibrary;getCompleteBuffer(Lnet/minecraft/resources/ResourceLocation;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<SoundBuffer> electromagnetism$play(SoundBufferLibrary library,ResourceLocation location,SoundInstance sound){return library.getCompleteBuffer(location).thenApply(buffer->{GameAudioCapture.play(sound,buffer);return buffer;});}
    @Inject(method="stop(Lnet/minecraft/client/resources/sounds/SoundInstance;)V",at=@At("HEAD"))
    private void electromagnetism$stop(SoundInstance sound,CallbackInfo ci){GameAudioCapture.stop(sound);}
}
