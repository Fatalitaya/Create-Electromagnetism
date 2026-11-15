package com.electromagnetism.client;

import com.electromagnetism.content.instruments.*;
import com.electromagnetism.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.*;
import net.minecraft.client.sounds.*;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import javax.sound.sampled.AudioFormat;
import java.nio.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Bounded playback of accepted samples; queue underruns are silent, never resimulated. */
public final class RadioPlayback {
    private static final Map<UUID,Receiver> RECEIVERS=new HashMap<>();
    private RadioPlayback(){}
    public static void reset(){for(var receiver:RECEIVERS.values())receiver.shutdown();RECEIVERS.clear();}
    public static void receive(AudioNetworking.Output frame){
        var mc=Minecraft.getInstance();
        if(mc.level==null||mc.player==null||!mc.level.isLoaded(frame.pos())||mc.player.distanceToSqr(frame.pos().getCenter())>256)return;
        if(!(mc.level.getBlockState(frame.pos()).getBlock() instanceof RadioBlock radio)||radio.transmitter())return;
        if(frame.samples().length==0){var old=RECEIVERS.remove(frame.instance());if(old!=null)old.shutdown();return;}
        RECEIVERS.entrySet().removeIf(entry->{if(entry.getValue().isStopped())return true;if(entry.getValue().world!=mc.level||entry.getValue().pos.equals(frame.pos())&&!entry.getKey().equals(frame.instance())){entry.getValue().shutdown();return true;}return false;});
        var sound=RECEIVERS.get(frame.instance());
        if(sound==null){if(RECEIVERS.size()>=16)return;sound=new Receiver(frame.pos());RECEIVERS.put(frame.instance(),sound);mc.getSoundManager().play(sound);}
        if(frame.end()<=sound.through)return;
        synchronized(sound.queue) {
            if(sound.through>=0 && frame.start()>sound.through+2.0/frame.sampleRate())sound.queue.clear();
            short[] samples=frame.samples();int skip=sound.through<0?0:(int)Math.max(0,Math.floor((sound.through-frame.start())*frame.sampleRate())+1);
            for(int i=skip;i<samples.length;i++){if(sound.queue.size()>=16000)sound.queue.removeFirst();sound.queue.addLast(samples[i]);}
        }
        sound.through=frame.end();sound.received=mc.level.getGameTime();
    }
    private static final class Receiver extends AbstractTickableSoundInstance {
        final BlockPos pos;final PcmRingBuffer queue=new PcmRingBuffer(16000);double through=-1;long received;
        final net.minecraft.client.multiplayer.ClientLevel world=Minecraft.getInstance().level;
        Receiver(BlockPos pos){super(ModSounds.NATIVE_RECEIVER.get(),SoundSource.BLOCKS,net.minecraft.util.RandomSource.create());this.pos=pos;x=pos.getX()+0.5;y=pos.getY()+0.5;z=pos.getZ()+0.5;looping=true;volume=1;pitch=1;}
        void shutdown(){stop();synchronized(queue){queue.clear();}}
        @Override public void tick(){var mc=Minecraft.getInstance();if(mc.level!=world||mc.level==null||mc.player==null||mc.level.getGameTime()-received>40||!mc.level.isLoaded(pos)||mc.player.distanceToSqr(pos.getCenter())>256||!(mc.level.getBlockState(pos).getBlock() instanceof RadioBlock radio)||radio.transmitter())shutdown();}
        @Override public CompletableFuture<AudioStream> getStream(SoundBufferLibrary library,Sound sound,boolean loop){
            return CompletableFuture.completedFuture(new AudioStream(){
                @Override public AudioFormat getFormat(){return new AudioFormat(16000,16,1,true,false);}
                @Override public ByteBuffer read(int count){
                    int bytes=Math.min(32000,Math.max(0,count/2*2));var data=ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
                    synchronized(queue){while(data.remaining()>=2)data.putShort(queue.isEmpty()?0:queue.removeFirst());}return data.flip();
                }
                @Override public void close(){synchronized(queue){queue.clear();}}
            });
        }
    }
}
