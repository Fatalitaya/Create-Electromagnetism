package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.config.ModConfigs;
import com.electromagnetism.simulation.SimulationProtocol.*;
import com.george_vi.electroenergetics.foundation.nodes.InWorldNode;
import com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData;
import net.minecraft.nbt.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Weather/contact facts only. Pulse clocks, arc temperature and body energy are native. */
public final class NativeEnvironment {
    private record Strike(UUID id,PortId port,double start,double duration,double peak,double rise,double fall,double continuing,double gap,double breakdown){
        private Strike {
            for(double value:new double[]{start,duration,peak,rise,fall,continuing,gap,breakdown})if(!Double.isFinite(value))throw new IllegalArgumentException("Nonfinite lightning event parameter");
            if(start<0||rise<=0||fall<=rise||duration<fall||peak<0||continuing<0||gap<=0||breakdown<=0)throw new IllegalArgumentException("Invalid lightning event parameters");
        }
    }
    private final List<Strike> strikes=new ArrayList<>();
    private final Map<UUID,UUID> exposed=new HashMap<>();
    private record Contact(com.george_vi.electroenergetics.foundation.nodes.InWorldNodeConnection wire,com.george_vi.electroenergetics.simulation.infrastructure.WireSimulationState.WireCutHandle handle,com.george_vi.electroenergetics.foundation.nodes.AttachedNode node){}
    private final Map<UUID,Contact> contacts=new HashMap<>();
    private long contactTick=Long.MIN_VALUE;
    private long tick=Long.MIN_VALUE;
    private double committedTime;
    private final Map<UUID,Double> effectCursors=new HashMap<>();
    public static PortId earth(InfrastructureSavedData sd){return new PortId(new DeviceId(UUID.nameUUIDFromBytes(("electromagnetism/earth/"+sd.level.dimension().location()).getBytes(StandardCharsets.UTF_8)),0),0);}
    private static UUID branch(UUID id,String role){return UUID.nameUUIDFromBytes((id+"/"+role).getBytes(StandardCharsets.UTF_8));}
    public void prepareContacts(InfrastructureSavedData sd){
        if(contactTick==sd.level.getGameTime())return;contactTick=sd.level.getGameTime();
        var active=new HashSet<UUID>();
        if(com.george_vi.electroenergetics.config.CEEConfigs.server().enableElectrocution.get()) {
            for(var entry:sd.wireSimulationState.getAllConnections()) {
                var geometry=entry.getValue();if(geometry.wireData.wireType().isDecorative()||geometry.wireData.wireType()==com.george_vi.electroenergetics.CEEWireTypes.DUPLEX.get())continue;
                var candidates=sd.level.getEntitiesOfClass(LivingEntity.class,geometry.bb.inflate(0.25),e->e.isAlive()&&!e.isSpectator());
                for(var entity:candidates) {
                    double best=Double.POSITIVE_INFINITY;int point=-1;var box=entity.getBoundingBox().inflate(0.1);
                    for(int i=0;i<geometry.points.size();i++){var p=geometry.points.get(i);double dx=Math.max(0,Math.max(box.minX-p.x,p.x-box.maxX)),dy=Math.max(0,Math.max(box.minY-p.y,p.y-box.maxY)),dz=Math.max(0,Math.max(box.minZ-p.z,p.z-box.maxZ));double distance=dx*dx+dy*dy+dz*dz;if(distance<best){best=distance;point=i;}}
                    if(point<0||best>0.01)continue;
                    UUID id=UUID.nameUUIDFromBytes(("wire-body/"+entity.getUUID()+"/"+((CeeWireIdentity)geometry.wireData).electromagnetism$wireIdentity()).getBytes(StandardCharsets.UTF_8));
                    if(active.size()>=256)throw new IllegalArgumentException("Wire-body contact limit exceeded");active.add(id);
                    float fraction=point/(float)Math.max(1,geometry.points.size()-1);var old=contacts.get(id);
                    if(old==null||!sd.wireSimulationState.cutExists(old.handle(),old.node())) {
                        if(old!=null&&old.handle().valid())sd.wireSimulationState.invalidateHandle(old.handle());
                        var handle=sd.wireSimulationState.createHandle("NativeBody/"+entity.getUUID());var node=sd.wireSimulationState.createCut(handle,entry.getKey(),fraction);
                        contacts.put(id,new Contact(entry.getKey(),handle,node));
                    } else sd.wireSimulationState.relocateCut(old.handle(),old.node(),fraction);
                }
            }
        }
        var iterator=contacts.entrySet().iterator();while(iterator.hasNext()){var entry=iterator.next();if(!active.contains(entry.getKey())){if(entry.getValue().handle().valid())sd.wireSimulationState.invalidateHandle(entry.getValue().handle());iterator.remove();}}
    }
    public Map<com.george_vi.electroenergetics.foundation.nodes.Node,UUID> stableContactIds(){var result=new HashMap<com.george_vi.electroenergetics.foundation.nodes.Node,UUID>();contacts.forEach((id,contact)->result.put(contact.node(),id));return result;}
    public void captureWireBodies(InfrastructureSavedData sd,NativeConductors.Capture conductors,List<DeviceInput> devices,List<Branch> branches){
        var earth=earth(sd);
        for(var entry:contacts.entrySet()) {
            var contact=entry.getValue();var endpoint=conductors.taps().get(contact.node());if(endpoint==null)throw new IllegalArgumentException("Native body wire contact not resolved");
            var geometry=sd.wireSimulationState.getConnection(contact.wire());if(geometry==null)throw new IllegalArgumentException("Body conductor disappeared during capture");
            UUID entity=null;String name=contact.handle().name;if(name.startsWith("NativeBody/"))entity=UUID.fromString(name.substring("NativeBody/".length()));if(entity==null)throw new IllegalArgumentException("Unknown native body contact owner");
            var body=endpoint;
            if(geometry.wireData.wireType().insulated()) {
                var identity=new DeviceId(entry.getKey(),0);body=new PortId(identity,0);devices.add(new DeviceInput(identity,"ports",List.of(body),Map.of()));
                var parameters=new HashMap<>(NativeAirGap.parameters(false,geometry.wireData.wireType().maxInsulationVoltage(),0.01));
                parameters.put("leakageSiemens",1.0/Math.max(1,geometry.wireData.wireType().insulationResistance()));
                branches.add(new Branch(branch(entry.getKey(),"insulation"),endpoint,body,"air_gap",parameters));
            }
            branches.add(new Branch(entry.getKey(),body,earth,"resistor",Map.of("resistanceOhms",(double)ModConfigs.server().nativeBodyOhms.getF())));exposed.put(entry.getKey(),entity);
        }
    }
    public boolean tickWeather(InfrastructureSavedData sd,Map<InWorldNode,PortId> ports,double nextTime){
        int before=strikes.size();
        long now=sd.level.getGameTime();
        if(tick!=now) {
            tick=now;
            if(strikes.size()>64)throw new IllegalStateException("Environment event capacity exceeded");
            if(ModConfigs.server().enableLightningStrikes.get()&&sd.level.isThundering()&&!ports.isEmpty()
                    && sd.level.random.nextDouble()<1.0/ModConfigs.server().lightningStrikeIntervalTicks.get()) {
                if(strikes.size()>=64)throw new IllegalStateException("Lightning event queue full");
                var candidate=ports.entrySet().stream().filter(e->sd.getNodeData(e.getKey())!=null&&sd.level.isLoaded(e.getKey().sourcePos())&&sd.level.canSeeSky(e.getKey().sourcePos().above()))
                        .max(Comparator.comparingInt(e->e.getKey().sourcePos().getY())).orElse(null);
                if(candidate!=null) {
                    var config=ModConfigs.server();double rise=config.nativeLightningRiseSeconds.getF(),fall=config.nativeLightningFallSeconds.getF();
                    if(fall<=rise)throw new IllegalArgumentException("Lightning fall time must exceed rise time");
                    var position=candidate.getKey().sourcePos();
                    int ground=sd.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,position.getX(),position.getZ());
                    double gap=Math.max(1,position.getY()-ground),duration=Math.max(20*fall,config.lightningContinuingCurrentTicks.get()*0.05);
                    strikes.add(new Strike(UUID.randomUUID(),candidate.getValue(),nextTime,duration,config.lightningImpulseCurrent.getF(),rise,fall,config.lightningContinuingCurrentTicks.get()>0?100:0,gap,config.flashoverVoltsPerBlock.getF()*gap));
                    sd.level.playSound(null,position,net.minecraft.sounds.SoundEvents.LIGHTNING_BOLT_THUNDER,net.minecraft.sounds.SoundSource.WEATHER,4,1);
                }
            }
        }
        return strikes.size()!=before;
    }
    public void capture(InfrastructureSavedData sd,Map<InWorldNode,PortId> ports,Set<DeviceId> ids,List<DeviceInput> devices,List<Branch> branches,double nextTime){
        exposed.clear();var earth=earth(sd);
        if(ids.add(earth.device()))devices.add(new DeviceInput(earth.device(),"ports",List.of(earth),Map.of()));
        Set<PortId> available=new HashSet<>(ports.values());
        strikes.removeIf(strike->!available.contains(strike.port()));
        for(var strike:strikes) {
            branches.add(new Branch(branch(strike.id(),"impulse"),strike.port(),earth,"lightning_impulse",Map.of("startSeconds",strike.start(),"peakAmperes",strike.peak(),"riseSeconds",strike.rise(),"fallSeconds",strike.fall(),"channelOhms",0.1,"continuingAmperes",strike.continuing(),"durationSeconds",strike.duration())));
            branches.add(new Branch(branch(strike.id(),"arc"),strike.port(),earth,"air_gap",NativeAirGap.parameters(false,strike.breakdown(),strike.gap())));
        }
        if(ports.size()>4096)throw new IllegalArgumentException("Environment endpoint capacity exceeded");
        if(!com.george_vi.electroenergetics.config.CEEConfigs.server().enableElectrocution.get())return;
        for(var entry:ports.entrySet()) {
            // Adapter-local circuit ports (e.g. the HV capacitor body) are not
            // exposed world contacts. Only CEE's registered terminals have geometry.
            if(sd.getNodeData(entry.getKey())==null)continue;
            var position=sd.getNodePosition(entry.getKey());if(position==null)continue;
            var nearby=sd.level.getEntitiesOfClass(LivingEntity.class,new AABB(position,position).inflate(0.25),entity->entity.isAlive()&&!entity.isSpectator());
            for(var entity:nearby) {
                if(exposed.size()>=256)throw new IllegalArgumentException("Body contact capacity exceeded");
                UUID id=UUID.nameUUIDFromBytes(("body/"+entity.getUUID()+"/"+entry.getValue()).getBytes(StandardCharsets.UTF_8));
                branches.add(new Branch(id,entry.getValue(),earth,"resistor",Map.of("resistanceOhms",(double)ModConfigs.server().nativeBodyOhms.getF())));exposed.put(id,entity.getUUID());
            }
        }
    }
    public void apply(InfrastructureSavedData sd,Observations observations){
        double end=observations.accepted().end();if(end<=committedTime)return;
        var doses=new HashMap<UUID,Double>();
        for(var reading:observations.readings()){var entity=exposed.get(reading.branch());if(entity!=null)doses.merge(entity,reading.dissipatedEnergy(),Double::sum);}
        for(var dose:doses.entrySet()) {
            if(effectCursors.getOrDefault(dose.getKey(),-1.0)>=end)continue;
            if(effectCursors.size()>=512&&!effectCursors.containsKey(dose.getKey()))throw new IllegalStateException("Body effect cursor capacity exceeded");
            // Mark each issued world effect before callbacks can re-enter this adapter.
            effectCursors.put(dose.getKey(),end);sd.setDirty();
            var entity=sd.level.getEntity(dose.getKey());
            if(entity instanceof LivingEntity living&&living.isAlive()&&dose.getValue()>0)living.hurt(sd.level.damageSources().lightningBolt(),(float)Math.min(Float.MAX_VALUE,dose.getValue()/ModConfigs.server().nativeShockJoulesPerDamage.getF()));
        }
        strikes.removeIf(strike->{var reading=observations.readings().stream().filter(r->r.branch().equals(branch(strike.id(),"arc"))).findFirst().orElse(null);
            if(reading==null)return false;
            if(reading.temperatureKelvin().orElse(0)>1293.15){var position=sd.deviceSD.getDevices().stream().filter(d->((CeeDeviceIdentity)d).electromagnetism$nativeIdentity().equals(strike.port().device())).findFirst().map(d->d.pos.getCenter()).orElse(null);if(position!=null)sd.level.sendParticles(net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK,position.x,position.y,position.z,2,0.1,0.1,0.1,0);}
            return end>=strike.start()+strike.duration()&&reading.temperatureKelvin().isPresent()&&reading.temperatureKelvin().getAsDouble()<293.150001;
        });
        committedTime=end;
        effectCursors.values().removeIf(time->time<=committedTime);
    }
    public CompoundTag save(){
        var tag=new CompoundTag();tag.putLong("WeatherTick",tick);tag.putDouble("CommittedTime",committedTime);var list=new ListTag();
        for(var strike:strikes){var item=new CompoundTag();item.putUUID("Identity",strike.id());item.putUUID("Owner",strike.port().device().instance());item.putLong("Generation",strike.port().device().generation());item.putInt("Terminal",strike.port().terminal());
            item.putDouble("Start",strike.start());item.putDouble("Duration",strike.duration());item.putDouble("Peak",strike.peak());item.putDouble("Rise",strike.rise());item.putDouble("Fall",strike.fall());item.putDouble("Continuing",strike.continuing());item.putDouble("Gap",strike.gap());item.putDouble("Breakdown",strike.breakdown());list.add(item);}
        tag.put("Strikes",list);var cursors=new ListTag();
        effectCursors.forEach((entity,time)->{var item=new CompoundTag();item.putUUID("Entity",entity);item.putDouble("End",time);cursors.add(item);});tag.put("EffectCursors",cursors);return tag;
    }
    public void restore(CompoundTag tag){
        if(!strikes.isEmpty())throw new IllegalStateException("Environment already initialized");
        tick=tag.getLong("WeatherTick");committedTime=tag.getDouble("CommittedTime");if(!Double.isFinite(committedTime)||committedTime<0)throw new IllegalArgumentException("Invalid environment effect cursor");
        effectCursors.clear();var cursors=tag.getList("EffectCursors",Tag.TAG_COMPOUND);if(cursors.size()>512)throw new IllegalArgumentException("Too many body effect cursors");
        for(var raw:cursors){var item=(CompoundTag)raw;double time=item.getDouble("End");if(!item.hasUUID("Entity")||!Double.isFinite(time)||time<committedTime||effectCursors.putIfAbsent(item.getUUID("Entity"),time)!=null)throw new IllegalArgumentException("Invalid body effect cursor");}
        var list=tag.getList("Strikes",Tag.TAG_COMPOUND);if(list.size()>64)throw new IllegalArgumentException("Environment snapshot exceeds event limit");var ids=new HashSet<UUID>();
        for(var value:list){var item=(CompoundTag)value;if(!item.hasUUID("Identity")||!item.hasUUID("Owner")||!ids.add(item.getUUID("Identity")))throw new IllegalArgumentException("Invalid environment event identity");
            strikes.add(new Strike(item.getUUID("Identity"),new PortId(new DeviceId(item.getUUID("Owner"),item.getLong("Generation")),item.getInt("Terminal")),item.getDouble("Start"),item.getDouble("Duration"),item.getDouble("Peak"),item.getDouble("Rise"),item.getDouble("Fall"),item.getDouble("Continuing"),item.getDouble("Gap"),item.getDouble("Breakdown")));
        }
    }
}
