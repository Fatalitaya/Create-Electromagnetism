package com.electromagnetism.client;

import com.electromagnetism.content.instruments.AudioNetworking;
import com.mojang.blaze3d.audio.SoundBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import javax.sound.sampled.AudioFormat;
import java.nio.*;
import java.util.*;

/**
 * Capture resolved sounds before listener-distance attenuation so they can be mixed at the
 * microphone position.
 */
@EventBusSubscriber(modid="electromagnetism",value=Dist.CLIENT)
public final class GameAudioCapture implements AudioInputSource {
    private static final Map<SoundBuffer,float[]> CACHE=Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<SoundInstance,Voice> VOICES=new IdentityHashMap<>();
    private static final Map<UUID,Capture> LEASES=new HashMap<>();
    private static final GameAudioCapture GAME=new GameAudioCapture();
    private static AudioInputSource source=GAME;
    private static net.minecraft.client.multiplayer.ClientLevel world;
    private static long pausedAt=-1,pausedNanos;
    private static long audioTime(){return (pausedAt<0?System.nanoTime():pausedAt)-pausedNanos;}
    private static void world(net.minecraft.client.multiplayer.ClientLevel next){if(world!=next){LEASES.clear();VOICES.clear();RadioPlayback.reset();world=next;pausedAt=-1;pausedNanos=0;}}
    private static final class Voice {float[] samples;long base;final long start;final boolean loop;Voice(float[] samples,boolean loop){this.samples=samples;this.loop=loop;start=audioTime();}}
    private static final class Capture {AudioNetworking.Lease lease;long sequence,expires;final double[] filter=new double[4];Capture(AudioNetworking.Lease lease,long expires){this.lease=lease;this.expires=expires;}}
    public static void source(AudioInputSource replacement){Objects.requireNonNull(replacement);if(source!=GAME)source.close();source=replacement;}
    public static void lease(AudioNetworking.Lease lease){
        if(!lease.active()){LEASES.remove(lease.device());return;}
        var mc=Minecraft.getInstance();world(mc.level);if(mc.level==null)return;
        var old=LEASES.get(lease.device());
        if(old!=null&&old.lease.epoch().equals(lease.epoch()))old.expires=mc.level.getGameTime()+40;
        else if(LEASES.size()<32)LEASES.put(lease.device(),new Capture(lease,mc.level.getGameTime()+40));
    }
    public static void cache(SoundBuffer buffer,ByteBuffer data,AudioFormat format){
        float[] samples=decode(data,format);if(samples==null)return;
        synchronized(CACHE){int total=CACHE.values().stream().mapToInt(a->a.length).sum();if(CACHE.size()>=64||total+samples.length>4_000_000)return;CACHE.put(buffer,samples);}
    }
    public static void play(SoundInstance sound,SoundBuffer buffer){
        if(sound.isRelative()||sound.getSource()==net.minecraft.sounds.SoundSource.MUSIC||sound.getLocation().getPath().startsWith("native_receiver"))return;
        float[] samples=CACHE.get(buffer);if(samples==null)return;
        Minecraft.getInstance().execute(()->{int total=VOICES.values().stream().mapToInt(v->v.samples.length).sum();if(VOICES.size()<64&&total+samples.length<=4_000_000)VOICES.put(sound,new Voice(samples,sound.isLooping()));});
    }
    public static void stop(SoundInstance sound){Minecraft.getInstance().execute(()->VOICES.remove(sound));}
    public static net.minecraft.client.sounds.AudioStream stream(SoundInstance sound,net.minecraft.client.sounds.AudioStream original){
        if(sound.isRelative()||sound.getSource()==net.minecraft.sounds.SoundSource.MUSIC||sound.getLocation().getPath().startsWith("native_receiver"))return original;
        return new net.minecraft.client.sounds.AudioStream(){
            @Override public AudioFormat getFormat(){return original.getFormat();}
            @Override public ByteBuffer read(int size)throws java.io.IOException {
                var data=original.read(size);var decoded=decode(data,original.getFormat());
                if(decoded!=null)Minecraft.getInstance().execute(()->{
                    var voice=VOICES.get(sound);
                    if(voice==null){if(VOICES.size()>=64)return;voice=new Voice(new float[0],false);VOICES.put(sound,voice);}
                    int keep=Math.min(voice.samples.length,Math.max(0,64000-decoded.length));
                    var joined=new float[keep+Math.min(64000,decoded.length)];
                    voice.base+=voice.samples.length-keep;
                    System.arraycopy(voice.samples,voice.samples.length-keep,joined,0,keep);
                    System.arraycopy(decoded,Math.max(0,decoded.length-64000),joined,keep,joined.length-keep);voice.samples=joined;
                });
                return data;
            }
            @Override public void close()throws java.io.IOException{original.close();stop(sound);}
        };
    }
    public static float[] decode(ByteBuffer raw,AudioFormat format){
        int channels=format.getChannels(),bits=format.getSampleSizeInBits();double rate=format.getSampleRate();
        if(channels<1||channels>2||bits!=16||!Double.isFinite(rate)||rate<8000||rate>192000||raw.remaining()>16*1024*1024)return null;
        var data=raw.duplicate().order(format.isBigEndian()?ByteOrder.BIG_ENDIAN:ByteOrder.LITTLE_ENDIAN);
        int frames=data.remaining()/(channels*2),count=(int)Math.min(480000,Math.floor(frames*16000.0/rate));if(count==0)return null;
        var result=new float[count];double[] filter=new double[4];double coefficient=1-Math.exp(-2*Math.PI*2000/rate);int output=0;
        for(int frame=0;frame<frames&&output<count;frame++) {
            double value=0;for(int ch=0;ch<channels;ch++)value+=data.getShort()/32768.0/channels;
            for(int pole=0;pole<4;pole++){filter[pole]+=coefficient*(value-filter[pole]);value=filter[pole];}
            while(output<count&&output*rate/16000.0<=frame)result[output++]=(float)value;
        }
        return result;
    }
    @Override public short[] capture(BlockPos microphone,int count,int rate){
        var output=new short[count];long now=audioTime();
        VOICES.entrySet().removeIf(entry->!Minecraft.getInstance().getSoundManager().isActive(entry.getKey()));
        var center=microphone.getCenter();
        record Prepared(Voice voice,double gain,double start,double step) {}
        var prepared=new ArrayList<Prepared>(VOICES.size());
        for(var entry:VOICES.entrySet()) {
            var sound=entry.getKey();var voice=entry.getValue();
            double distance=center.distanceTo(new net.minecraft.world.phys.Vec3(sound.getX(),sound.getY(),sound.getZ()));
            double volume=sound.getVolume(),radius=Math.max(1,volume)*sound.getSound().getAttenuationDistance();
            double gain=sound.getAttenuation()==SoundInstance.Attenuation.NONE?1:Math.max(0,1-distance/radius);
            double pitch=Math.clamp(sound.getPitch(),0.5f,2.0f);
            prepared.add(new Prepared(voice,Math.clamp(volume,0,4)*gain,((now-voice.start)*1e-9-0.05)*16000*pitch,16000*pitch/rate));
        }
        for(int i=0;i<count;i++) {
            double mixed=0;
            for(var input:prepared) {
                var voice=input.voice();long index=(long)Math.floor(input.start()+i*input.step());
                if(index<0||voice.samples.length==0)continue;
                if(voice.loop)index%=voice.samples.length;else index-=voice.base;
                if(index<0||index>=voice.samples.length)continue;
                mixed+=voice.samples[(int)index]*input.gain();
            }
            output[i]=(short)Math.round(Math.clamp(mixed,-1,1)*32767);
        }
        return output;
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        var mc=Minecraft.getInstance();
        world(mc.level);
        if(mc.level==null||mc.player==null){LEASES.clear();VOICES.clear();return;}
        if(mc.isPaused()){if(pausedAt<0)pausedAt=System.nanoTime();return;}
        if(pausedAt>=0){pausedNanos+=System.nanoTime()-pausedAt;pausedAt=-1;}
        LEASES.values().removeIf(c->c.expires<mc.level.getGameTime()||!mc.level.isLoaded(c.lease.pos())||mc.player.distanceToSqr(c.lease.pos().getCenter())>256);
        for(var capture:LEASES.values()) {
            short[] samples=source.capture(capture.lease.pos(),800,16000);if(samples.length!=800)continue;
            PacketDistributor.sendToServer(new AudioNetworking.Upload(capture.lease.device(),capture.lease.generation(),capture.lease.epoch(),capture.sequence++,samples));
        }
    }
}
