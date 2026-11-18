package com.electromagnetism.content.instruments;

import com.simibubi.create.foundation.utility.CreateLang;
import com.electromagnetism.core.signal.*;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Ephemeral instrument history. Only nearby subscribed players receive bounded deltas. */
public class InstrumentBlockEntity extends SmartBlockEntity implements com.electromagnetism.integration.cee.nativebridge.CeeWaveformConsumer,com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation {
    private WaveformBuffer waveform=new WaveformBuffer(1024);
    private UUID epoch=UUID.randomUUID();
    private final Map<UUID,Double> sentThrough=new HashMap<>();
    private static final class AudioDelivery {
        double through=-1;
        final Map<UUID,Double> listeners=new HashMap<>();
    }
    // One boundary contains at most 32 channels. Keep every cursor a retry can revisit.
    private final LinkedHashMap<UUID,AudioDelivery> audioChannels=new LinkedHashMap<>(32,0.75f,true);
    private boolean nativeAvailable;
    private boolean audioInputAvailable;
    public void audioInput(boolean available){if(audioInputAvailable!=available){audioInputAvailable=available;sendData();}}
    @Override public boolean addToGoggleTooltip(List<net.minecraft.network.chat.Component> tooltip,boolean sneak){
        if(getBlockState().getBlock() instanceof MicrophoneBlock){CreateLang.builder().add(net.minecraft.network.chat.Component.translatable(audioInputAvailable?"electromagnetism.audio.input_available":"electromagnetism.audio.input_missing")).forGoggles(tooltip);return true;}return false;
    }
    @Override protected void write(net.minecraft.nbt.CompoundTag tag,HolderLookup.Provider registry,boolean packet){super.write(tag,registry,packet);if(packet)tag.putBoolean("NativeAudioInput",audioInputAvailable);
        else if(!audioChannels.isEmpty()){
            var channels=new net.minecraft.nbt.ListTag();
            audioChannels.forEach((id,audio)->{
                var channel=new net.minecraft.nbt.CompoundTag();
                channel.putUUID("Channel",id);
                channel.putDouble("Through",audio.through);
                var listeners=new net.minecraft.nbt.CompoundTag();
                audio.listeners.forEach((player,end)->listeners.putDouble(player.toString(),end));
                channel.put("Listeners",listeners);
                channels.add(channel);
            });
            tag.put("AudioDelivery",channels);
        }
    }
    @Override protected void read(net.minecraft.nbt.CompoundTag tag,HolderLookup.Provider registry,boolean packet){super.read(tag,registry,packet);if(packet)audioInputAvailable=tag.getBoolean("NativeAudioInput");
        else {
            audioChannels.clear();
            for(var entry:tag.getList("AudioDelivery",net.minecraft.nbt.Tag.TAG_COMPOUND)){
                var channel=(net.minecraft.nbt.CompoundTag)entry;
                var audio=new AudioDelivery();
                audio.through=channel.getDouble("Through");
                var listeners=channel.getCompound("Listeners");
                for(String key:listeners.getAllKeys())audio.listeners.put(UUID.fromString(key),listeners.getDouble(key));
                audioChannels.put(channel.getUUID("Channel"),audio);
            }
        }
    }
    private long receivedAtGameTick=Long.MIN_VALUE;
    public InstrumentBlockEntity(BlockEntityType<?> type,BlockPos pos,BlockState state) { super(type,pos,state); }
    @Override public void addBehaviours(List<BlockEntityBehaviour> behaviours) {}
    public void resetObservations() { waveform=new WaveformBuffer(1024);epoch=UUID.randomUUID();sentThrough.clear(); }
    @Override public void electromagnetism$publishWaveform(com.electromagnetism.simulation.SimulationProtocol.Waveform frame) {
        if (!(level instanceof ServerLevel server) || !server.getServer().isSameThread())
            throw new IllegalStateException("Waveform publication requires the server thread");
        if (frame.samples().size() > 1024) throw new IllegalArgumentException("Waveform batch exceeds subscription limit");
        if(getBlockState().getBlock() instanceof RadioBlock radio && !radio.transmitter() && !frame.samples().isEmpty()){
            var audio=audioChannels.computeIfAbsent(frame.channel(),id->new AudioDelivery());
            if(audioChannels.size()>32)audioChannels.pollFirstEntry();
            double end=frame.samples().getLast().time();
            if(end>audio.through){
                setChanged();
                AudioNetworking.output(server,worldPosition,frame,audio.through,audio.listeners);
                audio.through=end;
                audio.listeners.clear();
            }
        }
        nativeAvailable=true;
        if (!epoch.equals(frame.channel())) sentThrough.clear();
        var samples=new ArrayList<>(frame.samples().stream().map(sample -> new WaveformBuffer.Sample(
                sample.time(), sample.value(), WaveformBuffer.Quality.valueOf(sample.quality().name()))).toList());
        if(frame.droppedSamples()>0 && !samples.isEmpty()) {
            var first=samples.getFirst();samples.set(0,new WaveformBuffer.Sample(first.timeSeconds(),OptionalDouble.empty(),WaveformBuffer.Quality.MISSING));
        }
        receive(frame.channel(), samples);
        sendObservations(server.getGameTime());
    }
    public void nativeUnavailable() {
        if(!nativeAvailable)return;
        nativeAvailable=false;resetObservations();
        if(level instanceof ServerLevel server) {
            if(getBlockState().getBlock() instanceof RadioBlock radio && !radio.transmitter()){
                var device=com.george_vi.electroenergetics.devices.device.DevicesSavedData.load(server).getDevice(worldPosition);
                if(device instanceof com.electromagnetism.integration.cee.nativebridge.CeeDeviceIdentity identity)AudioNetworking.stopOutput(server,worldPosition,identity.electromagnetism$nativeIdentity().instance());
            }
            int count=0;
            for(var player:server.players()) {
                if(ScopeNetworking.subscribed(player,worldPosition,server.getGameTime()) && count++<16)
                    ScopeNetworking.send(player,new ScopeNetworking.Frame(worldPosition,epoch,List.of()));
            }
        }
    }
    private void sendObservations(long tick) {
        if(tick%2==0 && level instanceof ServerLevel server) {
            var snapshot=waveform.snapshot();
            var active=new HashSet<UUID>();int observers=0;
            for(var player:server.players()) {
                if(!ScopeNetworking.subscribed(player,worldPosition,tick)||observers++>=16) continue;
                active.add(player.getUUID());double after=sentThrough.getOrDefault(player.getUUID(),-1.0);
                var pending=new ArrayList<>(snapshot.stream().filter(s->s.timeSeconds()>after).toList());
                if(pending.size()>256) {
                    pending=new ArrayList<>(pending.subList(pending.size()-256,pending.size()));
                    var first=pending.getFirst();pending.set(0,new WaveformBuffer.Sample(first.timeSeconds(),OptionalDouble.empty(),WaveformBuffer.Quality.MISSING));
                }
                if(!pending.isEmpty()) {
                    ScopeNetworking.send(player,new ScopeNetworking.Frame(worldPosition,epoch,pending));
                    sentThrough.put(player.getUUID(),pending.getLast().timeSeconds());
                }
            }
            sentThrough.keySet().retainAll(active);
        }
    }
    public List<WaveformBuffer.Sample> samples() { return waveform.snapshot(); }
    public boolean observationsStale() {
        return receivedAtGameTick==Long.MIN_VALUE || level==null || level.getGameTime()-receivedAtGameTick>5 || waveform.snapshot().isEmpty();
    }
    public void receive(UUID nextEpoch,List<WaveformBuffer.Sample> samples) {
        if(!epoch.equals(nextEpoch)) { waveform=new WaveformBuffer(1024);epoch=nextEpoch; }
        var existing=waveform.snapshot();double last=existing.isEmpty()?-1:existing.getLast().timeSeconds();
        for(var s:samples) if(s.timeSeconds()>last) {
            waveform.append(s);last=s.timeSeconds();
            if(level!=null)receivedAtGameTick=level.getGameTime();
        }
    }
}
