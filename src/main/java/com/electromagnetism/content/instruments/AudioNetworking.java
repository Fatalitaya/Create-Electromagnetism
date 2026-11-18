package com.electromagnetism.content.instruments;

import com.electromagnetism.ElectromagnetismMod;
import com.electromagnetism.integration.cee.nativebridge.CeeDeviceIdentity;
import com.george_vi.electroenergetics.devices.device.DevicesSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.*;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.*;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.*;
import java.util.function.Consumer;

/** One elected uploader per microphone. Client time never advances the simulation. */
public final class AudioNetworking {
    private AudioNetworking(){}
    public static Consumer<Lease> clientLease=lease->{};
    public static Consumer<Output> clientOutput=output->{};
    public record Output(BlockPos pos,UUID instance,double start,double end,int sampleRate,short[] samples) implements CustomPacketPayload {
        public Output {if(samples.length>1024||!Double.isFinite(start)||!Double.isFinite(end)||end<start||sampleRate!=16000)throw new IllegalArgumentException("Invalid receiver audio output");samples=samples.clone();}
        @Override public short[] samples(){return samples.clone();}
        public static final Type<Output> TYPE=new Type<>(ElectromagnetismMod.rl("audio_output"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Output> CODEC=StreamCodec.of((b,p)->{b.writeBlockPos(p.pos());b.writeUUID(p.instance());b.writeDouble(p.start());b.writeDouble(p.end());b.writeVarInt(p.sampleRate());b.writeVarInt(p.samples.length);for(short sample:p.samples)b.writeShort(sample);},b->{var pos=b.readBlockPos();var instance=b.readUUID();double start=b.readDouble(),end=b.readDouble();int rate=b.readVarInt(),count=b.readVarInt();if(count<0||count>1024||b.readableBytes()!=count*2)throw new IllegalArgumentException("Receiver audio packet length mismatch");var samples=new short[count];for(int i=0;i<count;i++)samples[i]=b.readShort();return new Output(pos,instance,start,end,rate,samples);});
        @Override public Type<Output> type(){return TYPE;}
    }
    public static void output(ServerLevel level,BlockPos pos,com.electromagnetism.simulation.SimulationProtocol.Waveform frame,
                              double deliveredThrough,Map<UUID,Double> listenerProgress){
        int count=0;
        for(var player:level.players()) {
            if(player.distanceToSqr(pos.getCenter())>256 || count++>=16)continue;
            double after=listenerProgress.getOrDefault(player.getUUID(),deliveredThrough);
            var samples=frame.samples().stream().filter(sample->sample.time()>after).toList();
            if(samples.isEmpty())continue;
            var pcm=new short[samples.size()];
            for(int i=0;i<pcm.length;i++)pcm[i]=(short)Math.round(Math.clamp(samples.get(i).value().orElse(0),-1,1)*32767);
            double end=samples.getLast().time();
            var packet=new Output(pos,frame.positive().device().instance(),samples.getFirst().time(),end,16000,pcm);
            PacketDistributor.sendToPlayer(player,packet);
            listenerProgress.put(player.getUUID(),end);
        }
    }
    public static void stopOutput(ServerLevel level,BlockPos pos,UUID instance){
        var packet=new Output(pos,instance,0,0,16000,new short[0]);int count=0;
        for(var player:level.players())if(player.distanceToSqr(pos.getCenter())<=256&&count++<16)PacketDistributor.sendToPlayer(player,packet);
    }
    private record Key(ResourceKey<Level> dimension,UUID device){}
    private static final class State {
        final BlockPos pos;final long generation;UUID player,epoch=UUID.randomUUID();
        long sequence=-1,lastPacket=Long.MIN_VALUE,lastTick=Long.MIN_VALUE,lastLease=Long.MIN_VALUE;
        int packets;short[] samples;
        long frozenTick=Long.MIN_VALUE;Map<String,Double> frozen;
        long consumed=-1;
        State(BlockPos pos,long generation){this.pos=pos;this.generation=generation;}
    }
    private static final Map<Key,State> STATES=new HashMap<>();
    public record Lease(BlockPos pos,UUID device,long generation,UUID epoch,boolean active) implements CustomPacketPayload {
        public static final Type<Lease> TYPE=new Type<>(ElectromagnetismMod.rl("audio_lease"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Lease> CODEC=StreamCodec.of((b,p)->{b.writeBlockPos(p.pos());b.writeUUID(p.device());b.writeLong(p.generation());b.writeUUID(p.epoch());b.writeBoolean(p.active());},b->new Lease(b.readBlockPos(),b.readUUID(),b.readLong(),b.readUUID(),b.readBoolean()));
        @Override public Type<Lease> type(){return TYPE;}
    }
    public record Upload(UUID device,long generation,UUID epoch,long sequence,short[] samples) implements CustomPacketPayload {
        public Upload {if(samples.length!=800||sequence<0||generation<0)throw new IllegalArgumentException("Invalid audio frame");samples=samples.clone();}
        @Override public short[] samples(){return samples.clone();}
        public static final Type<Upload> TYPE=new Type<>(ElectromagnetismMod.rl("audio_upload"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Upload> CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.device());b.writeLong(p.generation());b.writeUUID(p.epoch());b.writeLong(p.sequence());for(short sample:p.samples)b.writeShort(sample);},b->{var device=b.readUUID();long generation=b.readLong();var epoch=b.readUUID();long sequence=b.readLong();if(b.readableBytes()!=1600)throw new IllegalArgumentException("Audio frame length mismatch");var samples=new short[800];for(int i=0;i<800;i++)samples[i]=b.readShort();return new Upload(device,generation,epoch,sequence,samples);});
        @Override public Type<Upload> type(){return TYPE;}
    }
    public static void register(RegisterPayloadHandlersEvent event){
        var registrar=event.registrar(net.neoforged.fml.ModList.get().getModContainerById(ElectromagnetismMod.ID).orElseThrow().getModInfo().getVersion().toString());
        registrar.playToClient(Lease.TYPE,Lease.CODEC,(lease,context)->context.enqueueWork(()->clientLease.accept(lease)));
        registrar.playToClient(Output.TYPE,Output.CODEC,(output,context)->context.enqueueWork(()->clientOutput.accept(output)));
        registrar.playToServer(Upload.TYPE,Upload.CODEC,(upload,context)->context.enqueueWork(()->{
            if(!(context.player() instanceof ServerPlayer player))return;
            var state=STATES.get(new Key(player.level().dimension(),upload.device()));
            if(state==null||!player.getUUID().equals(state.player)||!state.epoch.equals(upload.epoch())||state.generation!=upload.generation()||upload.sequence()<=state.sequence
                    ||player.isSpectator()||!player.level().isLoaded(state.pos)||player.distanceToSqr(state.pos.getCenter())>256)return;
            var device=DevicesSavedData.load(player.serverLevel()).getDevice(state.pos);
            if(!(device instanceof MicrophoneDevice)||!((CeeDeviceIdentity)device).electromagnetism$nativeIdentity().instance().equals(upload.device()))return;
            long tick=player.level().getGameTime();if(state.lastTick!=tick){state.lastTick=tick;state.packets=0;}
            if(++state.packets>2)return;
            state.sequence=upload.sequence();state.samples=upload.samples();state.lastPacket=tick;
        }));
    }
    public static void tick(ServerLevel level,DevicesSavedData devices){
        long tick=level.getGameTime();var present=new HashSet<Key>();
        for(var device:devices.getDevices()) {
            if(!(device instanceof MicrophoneDevice)||!level.isLoaded(device.pos))continue;
            var id=((CeeDeviceIdentity)device).electromagnetism$nativeIdentity();var key=new Key(level.dimension(),id.instance());present.add(key);
            var prior=STATES.get(key);
            if(prior!=null&&(!prior.pos.equals(device.pos)||prior.generation!=id.generation())){
                var oldPlayer=prior.player==null?null:level.getServer().getPlayerList().getPlayer(prior.player);
                if(oldPlayer!=null)PacketDistributor.sendToPlayer(oldPlayer,new Lease(prior.pos,key.device(),prior.generation,prior.epoch,false));
                STATES.remove(key);
            }
            if(!STATES.containsKey(key)&&STATES.size()>=128)continue;
            var state=STATES.computeIfAbsent(key,k->new State(device.pos,id.generation()));
            var current=state.player==null?null:level.getServer().getPlayerList().getPlayer(state.player);
            if(current==null||current.level()!=level||current.isSpectator()||current.distanceToSqr(device.pos.getCenter())>256) {
                if(current!=null)PacketDistributor.sendToPlayer(current,new Lease(state.pos,key.device(),state.generation,state.epoch,false));
                current=level.players().stream().filter(p->!p.isSpectator()&&p.distanceToSqr(device.pos.getCenter())<=256)
                        .min(Comparator.comparingDouble((ServerPlayer p)->p.distanceToSqr(device.pos.getCenter())).thenComparing(ServerPlayer::getUUID)).orElse(null);
                state.player=current==null?null:current.getUUID();state.epoch=UUID.randomUUID();state.sequence=-1;state.consumed=-1;state.samples=null;state.lastLease=Long.MIN_VALUE;
            }
            if(current!=null&&(state.lastLease==Long.MIN_VALUE||tick-state.lastLease>=20)){PacketDistributor.sendToPlayer(current,new Lease(state.pos,key.device(),state.generation,state.epoch,true));state.lastLease=tick;}
        }
        var iterator=STATES.entrySet().iterator();while(iterator.hasNext()){
            var entry=iterator.next();if(entry.getKey().dimension().equals(level.dimension())&&!present.contains(entry.getKey())){
                var state=entry.getValue();var player=state.player==null?null:level.getServer().getPlayerList().getPlayer(state.player);
                if(player!=null)PacketDistributor.sendToPlayer(player,new Lease(state.pos,entry.getKey().device(),state.generation,state.epoch,false));iterator.remove();
            }
        }
    }
    public static Map<String,Double> input(ServerLevel level,MicrophoneDevice device,double time){
        var id=((CeeDeviceIdentity)device).electromagnetism$nativeIdentity();var state=STATES.get(new Key(level.dimension(),id.instance()));
        if(state!=null && state.frozenTick==level.getGameTime() && state.frozen!=null)return state.frozen;
        var parameters=new HashMap<String,Double>();parameters.put("startSeconds",time);parameters.put("sampleSeconds",1.0/16000);parameters.put("peakVolts",1.0);parameters.put("resistanceOhms",600.0);
        var samples=state!=null&&state.samples!=null&&state.sequence>state.consumed&&level.getGameTime()-state.lastPacket<=2?state.samples:null;
        if(state!=null&&samples!=null)state.consumed=state.sequence;
        for(int i=0;i<800;i++)parameters.put("sample"+i,samples==null?0:samples[i]/32768.0);
        device.audioAvailable=samples!=null;
        if(level.getBlockEntity(device.pos) instanceof InstrumentBlockEntity instrument)instrument.audioInput(samples!=null);
        var frozen=Map.copyOf(parameters);if(state!=null){state.frozen=frozen;state.frozenTick=level.getGameTime();}return frozen;
    }
    public static void clear(){STATES.clear();}
}
