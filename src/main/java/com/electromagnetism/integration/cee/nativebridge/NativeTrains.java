package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.SimulationProtocol.*;
import com.george_vi.electroenergetics.mixin_interfaces.*;
import com.george_vi.electroenergetics.foundation.nodes.*;
import com.george_vi.electroenergetics.simulation.infrastructure.*;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.createmod.catnip.math.VecHelper;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Create supplies train velocity (blocks/tick, with one block = one metre).
 * Native supplies finite traction force; Java never integrates a second velocity. */
public final class NativeTrains {
    private record Contact(Train train,InWorldNodeConnection wire,WireSimulationState.WireCutHandle handle,AttachedNode node){}
    private final Map<UUID,Contact> contacts=new HashMap<>();
    private final Set<Train> active=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Train> lastActive=Collections.newSetFromMap(new IdentityHashMap<>());
    private static UUID branch(UUID id,String role){return UUID.nameUUIDFromBytes((id+"/native-train/"+role).getBytes(StandardCharsets.UTF_8));}
    public Map<Node,UUID> prepare(InfrastructureSavedData sd){
        active.clear();var used=new HashSet<UUID>();
        if(Create.RAILWAYS.trains.size()>256)throw new IllegalArgumentException("Native train capacity exceeded");
        for(var train:Create.RAILWAYS.trains.values()) {
            for(int carriageIndex=0;carriageIndex<train.carriages.size();carriageIndex++) {
                var carriage=train.carriages.get(carriageIndex);var electrical=(IPantographList)carriage;
                if(!electrical.electroEnergetics$hasElectricMotor())continue;
                var dimension=carriage.getDimensionalIfPresent(sd.level.dimension());if(dimension==null)continue;
                if(carriage.presentInMultipleDimensions())throw new IllegalArgumentException("Electric train spans dimension boundary");
                active.add(train);
                var head=dimension.rotationAnchors.getFirst();var tail=dimension.rotationAnchors.getSecond();if(head==null||tail==null)continue;
                double dx=head.x-tail.x,dy=head.y-tail.y,dz=head.z-tail.z;
                double yaw=Math.toDegrees(Math.atan2(dz,dx))+180,pitch=-Math.toDegrees(Math.atan2(dy,Math.sqrt(dx*dx+dz*dz)));
                var pivot=carriage.isOnTwoBogeys()?head:head.add(tail).scale(0.5);
                for(var pantograph:electrical.electroEnergetics$getPantographList()) {
                    if(!pantograph.active)continue;
                    UUID id=branch(train.id,"pantograph/"+carriageIndex+"/"+pantograph.originalPos.asLong());
                    var local=Vec3.atLowerCornerOf(pantograph.rotatedPos).add((pantograph.facingForward?1:-1)*pantograph.type.backOffset,pantograph.type.reach/2+pantograph.type.topOffset,0);
                    var position=pivot.add(VecHelper.rotate(VecHelper.rotate(local,-pitch,Direction.Axis.Z),-yaw+180,Direction.Axis.Y));
                    InWorldNodeConnection selected=null;float fraction=0;double best=Double.POSITIVE_INFINITY;
                    for(var wire:sd.wireSimulationState.getAllConnections()) {
                        var geometry=wire.getValue();if(geometry.wireData.wireType().isDecorative())continue;
                        var delta=geometry.pos2.subtract(geometry.pos1);double denominator=delta.lengthSqr();if(denominator<=0)continue;
                        double progress=Math.clamp(position.subtract(geometry.pos1).dot(delta)/denominator,0,1);
                        var closest=geometry.pos1.add(delta.scale(progress));var displacement=position.subtract(closest);
                        var relative=VecHelper.rotate(VecHelper.rotate(displacement,yaw+180,Direction.Axis.Y),-pitch,Direction.Axis.X);
                        if(Math.abs(relative.z)>pantograph.type.sidewaysReach||Math.abs(relative.y)>pantograph.type.reach/2||Math.abs(relative.x)>Math.max(0.125,(relative.y+pantograph.type.reach/2)*0.2+0.125))continue;
                        double distance=position.distanceToSqr(closest);if(distance<best){best=distance;selected=wire.getKey();fraction=(float)progress;}
                    }
                    if(selected==null)continue;
                    var old=contacts.get(id);if(old==null||!old.wire().equals(selected)||!sd.wireSimulationState.cutExists(old.handle(),old.node())) {
                        if(old!=null&&old.handle().valid())sd.wireSimulationState.invalidateHandle(old.handle());
                        var handle=sd.wireSimulationState.createHandle("NativeTrain/"+id);var node=sd.wireSimulationState.createCut(handle,selected,fraction);
                        contacts.put(id,new Contact(train,selected,handle,node));
                    }else sd.wireSimulationState.relocateCut(old.handle(),old.node(),fraction);
                    used.add(id);
                }
            }
        }
        var iterator=contacts.entrySet().iterator();while(iterator.hasNext()){var entry=iterator.next();if(!used.contains(entry.getKey())){if(entry.getValue().handle().valid())sd.wireSimulationState.invalidateHandle(entry.getValue().handle());iterator.remove();}}
        var stable=new HashMap<Node,UUID>();contacts.forEach((id,value)->stable.put(value.node(),id));return stable;
    }
    public void capture(InfrastructureSavedData sd,NativeConductors.Capture conductors,List<DeviceInput> devices,List<Branch> branches,Set<DeviceId> identities){
        var earth=NativeEnvironment.earth(sd);if(identities.add(earth.device()))devices.add(new DeviceInput(earth.device(),"ports",List.of(earth),Map.of()));
        for(var train:active) {
            var id=new DeviceId(branch(train.id,"motor-owner"),0);if(!identities.add(id))throw new IllegalArgumentException("Duplicate train motor owner");
            var p=java.util.stream.IntStream.range(0,8).mapToObj(i->new PortId(id,i)).toList();devices.add(new DeviceInput(id,"ports",p,Map.of()));
            var trainData=((ICEETrainExtension)train).getElectricTrainData();
            if(trainData.accumulators<0||trainData.accumulators>4096)throw new IllegalArgumentException("Invalid train battery bank size");
            if(trainData.accumulators>0){
                // A train owns one aggregate 3 kV bank. Adding parallel cells changes
                // its capacitance at an accepted boundary, preserving total charge.
                // Legacy tick-count charge is never imported as coulombs.
                double capacitance=com.electromagnetism.config.ModConfigs.server().nativeCellCapacityCoulombs.getF()/3000*trainData.accumulators;
                branches.add(new Branch(branch(train.id,"battery-charge"),p.get(5),p.get(2),"capacitor",Map.of("capacitanceFarads",capacitance)));
                branches.add(new Branch(branch(train.id,"battery-gate"),p.get(6),p.get(5),"battery_contact",Map.of("resistanceOhms",0.01,"fullVolts",3000.0,"taperVolts",150.0),List.of(p.get(2))));
                branches.add(new Branch(branch(train.id,"battery-copper"),p.get(0),p.get(6),"resistor",Map.of("resistanceOhms",5.0/trainData.accumulators)));
            }
            double direction=Math.signum(train.targetSpeed==0?train.speed:train.targetSpeed);if(direction==0)direction=1;
            double speed=train.derailed?0:train.speed*20*direction;
            branches.add(new Branch(branch(train.id,"main"),p.get(1),p.get(2),"shaft_boundary",Map.of("newtonMetresPerAmpere",50.0,"radiansPerSecond",speed)));
            branches.add(new Branch(branch(train.id,"copper"),p.get(0),p.get(3),"resistor",Map.of("resistanceOhms",5.0)));
            branches.add(new Branch(branch(train.id,"flux"),p.get(3),p.get(1),"inductor",Map.of("inductanceHenries",0.05)));
            branches.add(new Branch(branch(train.id,"rail-return"),p.get(2),earth,"resistor",Map.of("resistanceOhms",0.1)));
            if(((ICEETrainExtension)train).getElectricTrainData().hasCreativeSource)branches.add(new Branch(branch(train.id,"creative"),p.get(0),p.get(2),"dc_voltage_source",Map.of("voltageVolts",3000.0)));
            for(var entry:contacts.entrySet())if(entry.getValue().train()==train) {
                var cut=conductors.taps().get(entry.getValue().node());if(cut==null)throw new IllegalArgumentException("Train current collection point missing");
                branches.add(new Branch(branch(entry.getKey(),"contact"),cut,p.get(0),"resistor",Map.of("resistanceOhms",0.01)));
            }
        }
    }
    private static void sync(InfrastructureSavedData sd,Train train){
        var data=((ICEETrainExtension)train).getElectricTrainData();
        for(var carriage:train.carriages){var dimension=carriage.getDimensionalIfPresent(sd.level.dimension());if(dimension==null||dimension.entity==null)continue;var entity=dimension.entity.get();if(entity==null)continue;
            net.createmod.catnip.platform.CatnipServices.NETWORK.sendToClientsAround(sd.level,entity.position(),100,new com.george_vi.electroenergetics.content.railway_electrification.gauges.SyncTrainGaugeDataPacket(train.id,data.lastVoltage,data.displayCurrent));break;}
    }
    private static void unavailable(InfrastructureSavedData sd,Train train){unavailable(sd,train,true);}
    private static void unavailable(InfrastructureSavedData sd,Train train,boolean clearImpulse){
        if(clearImpulse)((NativeTrainBoundary)train).electromagnetism$tractionUnavailable();
        var data=((ICEETrainExtension)train).getElectricTrainData();if(!Double.isFinite(data.lastVoltage)&&!Double.isFinite(data.displayCurrent))return;
        data.lastVoltage=data.displayCurrent=data.accumulatorCharge=Double.NaN;sync(sd,train);
    }
    public void apply(InfrastructureSavedData sd,Map<UUID,Reading> readings,Accepted accepted){
        if(accepted.end()==accepted.start())return;
        for(var train:active) {
            var shaft=readings.get(branch(train.id,"main"));var copper=readings.get(branch(train.id,"copper"));var flux=readings.get(branch(train.id,"flux"));
            if(shaft==null)continue; // Another domain or an obsolete mechanical interval cannot revoke this train.
            boolean valid=copper!=null&&flux!=null&&shaft.torqueNewtonMetres().isPresent();
            double direction=Math.signum(train.targetSpeed==0?train.speed:train.targetSpeed);if(direction==0)direction=1;
            double force=valid?direction*shaft.torqueNewtonMetres().getAsDouble():0;
            var boundary=(NativeTrainBoundary)train;
            if(valid)boundary.electromagnetism$traction(accepted.revision().session(),accepted.start(),accepted.end(),force,sd.level.getServer().getTickCount());
            else boundary.electromagnetism$tractionUnavailable();
            var display=((ICEETrainExtension)train).getElectricTrainData();display.lastVoltage=valid?shaft.meanVoltage()+copper.meanVoltage()+flux.meanVoltage():Double.NaN;display.displayCurrent=valid?shaft.rmsCurrent():Double.NaN;
            var charge=readings.get(branch(train.id,"battery-charge"));display.accumulatorCharge=display.accumulators==0?0:charge==null?Double.NaN:charge.meanVoltage()/3000*display.accumulators;
            sync(sd,train);
            if(valid&&force*direction>1&&train.fuelTicks<2)train.fuelTicks=2;
        }
        for(var train:lastActive)if(!active.contains(train))unavailable(sd,train);lastActive.clear();lastActive.addAll(active);
    }
    public void invalidate(InfrastructureSavedData sd,boolean clearImpulse){for(var train:active)unavailable(sd,train,clearImpulse);for(var train:lastActive)unavailable(sd,train,clearImpulse);}
}
