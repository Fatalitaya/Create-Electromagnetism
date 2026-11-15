package com.electromagnetism.integration.cee;

import com.electromagnetism.integration.cee.nativebridge.*;
import com.electromagnetism.simulation.SimulationProtocol.Status;
import com.electromagnetism.content.electronics.bjt.TransistorDevice;
import com.electromagnetism.registry.ModDeviceTypes;
import com.george_vi.electroenergetics.devices.device.*;
import com.george_vi.electroenergetics.foundation.device.SimpleElectricalDevice;
import com.george_vi.electroenergetics.simulation.*;
import com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Opt-in disposable-server tests of transformed entry points and accepted native world physics. */
@EventBusSubscriber(modid = "electromagnetism")
public final class NativeMixinChecks {
    @SubscribeEvent public static void started(ServerStartedEvent event) throws Exception {
        if (!Boolean.getBoolean("electromagnetism.verifyNativeInterfaces")) return;
        var server = event.getServer();
        var level = server.overworld();
        var devices = DevicesSavedData.load(level);
        var pos = new BlockPos(8, 250, 8);
        var result = new com.google.gson.JsonObject();
        try {
            if(Boolean.getBoolean("electromagnetism.verifyPackagedServer")) {
                var source=com.electromagnetism.simulation.JniSimulationBackend.class.getProtectionDomain().getCodeSource().getLocation().toString();
                require(source.contains("native-server-verification.jar"),"Packaged server accidentally loaded development classes: "+source);
                result.addProperty("javaArtifact",source);
            }
            result.addProperty("ceeJarSha256",NativeCoverageChecks.verifyDependency());
            String scope=System.getProperty("electromagnetism.nativeInterfaceCase", "all");
            result.addProperty("scope",scope);
            if(!scope.equals("all")) {
                if(!scope.startsWith("verify"))throw new IllegalArgumentException("Unknown native case: "+scope);
                var test=NativeMixinChecks.class.getDeclaredMethod(scope,net.minecraft.server.level.ServerLevel.class,DevicesSavedData.class);
                test.invoke(null,level,devices);
                result.addProperty("passed",true);
                System.out.println("NATIVE_INTERFACE_CASE_PASSED: "+scope);
                return; // No full-suite marker or coverage claim for a selected case.
            }
            if (devices.getDevice(pos) != null) throw new AssertionError("Disposable fixture position occupied");
            var type = new SimulatedDeviceType<Probe>(ResourceLocation.fromNamespaceAndPath("electromagnetism", "native_probe"),
                    (t, l, p, d) -> new Probe(l, p, d, t));
            var probe = devices.addDevice(type, pos, new CompoundTag());
            var ticker = InfrastructureSavedData.load(level).ticker;
            var access = (CeeSimulationAccess)ticker;

            var sentinel = new CompletableFuture<SimulationResults>() {
                @Override public SimulationResults get() { throw new AssertionError("Legacy Future.get reached"); }
            };
            ticker.future = sentinel;
            ticker.tick(); ticker.endTick();
            require(ticker.future == sentinel && ticker.lastResults == null, "Legacy worker/result publication reached");
            ticker.future = null;
            devices.tick();
            require(probe.initialized == 1 && probe.pre == 0 && probe.post == 0, "Lifecycle/legacy boundary violation");
            require(!access.electromagnetism$inspectInputs().blockers().isEmpty(), "Unsupported device silently accepted");
            require(access.electromagnetism$nativeStatus().status()==Status.UNSUPPORTED,"Unknown world device was not diagnosed");
            var one = new TransistorDevice(level,pos,devices,ModDeviceTypes.NPN_TRANSISTOR.get());
            var two = new TransistorDevice(level,pos,devices,ModDeviceTypes.NPN_TRANSISTOR.get());
            require(!((CeeDeviceIdentity)one).electromagnetism$nativeIdentity().equals(((CeeDeviceIdentity)two).electromagnetism$nativeIdentity()), "Coordinate reused as identity");
            var old = new CompoundTag(); old.putInt("ChargeModelVersion",1); old.putDouble("Q0",0.125);
            one.read(old); old.putDouble("Q0",99); var saved = new CompoundTag(); one.write(saved);
            require(saved.getDouble("Q0") == 0.125, "Legacy charge lost or aliased");
            require(one.electromagnetism$captureInput().ports().size() == 3, "Transistor ports lost");
            verifyIdentityPersistence(devices, level);
            for (String target : new String[]{"AlternatorBrushesDevice", "ThreePhaseAlternatorBrushesDevice"}) {
                var clazz = Class.forName("com.george_vi.electroenergetics.content.rotor." + target);
                require(CeeDeviceSource.class.isAssignableFrom(clazz), "Brush input mixin missing");
                var brush = (SimulatedDevice)clazz.getConstructor(Level.class,BlockPos.class,DevicesSavedData.class,SimulatedDeviceType.class)
                        .newInstance(level,pos,devices,ModDeviceTypes.NPN_TRANSISTOR.get());
                var legacy = new CompoundTag(); legacy.putInt("AcStateVersion",1); legacy.putDouble("AcJoules",42); legacy.putDouble("AcPhase",0.3);
                brush.read(legacy); var copy = new CompoundTag(); brush.write(copy);
                require(copy.getDouble("AcJoules") == 42 && copy.getDouble("AcPhase") == 0.3, "Brush legacy state lost");
            }
            for (String target : new String[]{"AlternatorBrushesBlockEntity", "AlternatorRotorBlockEntity"})
                require(CeeMechanicalSource.class.isAssignableFrom(Class.forName("com.george_vi.electroenergetics.content.rotor." + target)), "Mechanical mixin missing");
            devices.removeDevice(pos);
            access.electromagnetism$resumeMissingInputs();
            verifyWorkingCircuit(level,devices);
            verifyAcceptedTrainTraction(level,devices);
            verifyVoltageRequest(level,devices);
            verifySemiconductors(level,devices);
            verifyTerminalAndReversingSwitch(level,devices);
            verifyBundledWire(level,devices);
            verifyWireAttachments(level,devices);
            verifyDiodeAndEarth(level,devices);
            verifyStorageTransients(level,devices);
            verifyGeneratorScope(level,devices);
            verifyGauges(level,devices);
            verifyClampMeter(level,devices);
            verifyFrequencyMeter(level,devices);
            verifySynchroscope(level,devices);
            verifyEnergyMeters(level,devices);
            verifyFuse(level,devices);
            verifySf6Breaker(level,devices);
            verifyTransformer(level,devices);
            verifyVariac(level,devices);
            verifyCurrentTransformer(level,devices);
            verifyTransformerCore(level,devices);
            verifyConcretePole(level,devices);
            verifyFuseHolder(level,devices);
            verifyCommandedSwitches(level,devices);
            verifySableTransfer(level,devices);
            verifyBell(level,devices);
            verifyPotentiometer(level,devices);
            verifyCeeHeater(level,devices);
            verifyCeeBulb(level,devices);
            verifyIndicatorsAndBuzzer(level,devices);
            if(Boolean.getBoolean("electromagnetism.benchmarkNativeWorld"))NativeWorldBenchmark.run(level,devices);
            var coverage=NativeCoverageChecks.write();
            result.addProperty("coverageComplete",coverage.get("complete").getAsBoolean());
            result.addProperty("devicesWithWorldRegression",coverage.get("devicesWithWorldRegression").getAsInt());
            result.addProperty("registeredDevices",coverage.get("registeredCeeDevices").getAsInt()+coverage.get("registeredOwnDevices").getAsInt());
            result.addProperty("passed",true); result.addProperty("preTickCalls",probe.pre); result.addProperty("postTickCalls",probe.post);
            result.addProperty("initializeCalls",probe.initialized); result.addProperty("backendStatus","NATIVE_CIRCUIT_ACCEPTED");
            System.out.println("NATIVE_INTERFACE_MIXINS_PASSED");
        } catch (Throwable failure) {
            result.addProperty("passed",false); result.addProperty("failure",failure.toString());
            throw failure;
        } finally {
            devices.removeDevice(pos);
            Files.writeString(Path.of("native-interface-result.json"), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(result));
            server.halt(false);
        }
    }
    private static void verifyAcceptedTrainTraction(net.minecraft.server.level.ServerLevel level,DevicesSavedData devices) {
        var bogey=new com.simibubi.create.content.trains.entity.CarriageBogey(com.simibubi.create.AllBlocks.SMALL_BOGEY.get(),false,new CompoundTag());
        var carriage=new com.simibubi.create.content.trains.entity.Carriage(bogey,null,0);
        ((com.george_vi.electroenergetics.mixin_interfaces.IPantographList)carriage).electroEnergetics$setElectricMotor(true);
        var train=new com.simibubi.create.content.trains.entity.Train(UUID.randomUUID(),UUID.randomUUID(),null,java.util.List.of(carriage),java.util.List.of(),false,0);
        var boundary=(NativeTrainBoundary)train;var session=UUID.randomUUID();
        com.simibubi.create.Create.RAILWAYS.trains.put(train.id,train);
        try {
            train.targetSpeed=1;train.fuelTicks=100;
            boundary.electromagnetism$traction(session,0,0.001,1000,level.getServer().getTickCount());
            double expected=1.0/10000/20;
            require(Math.abs(train.acceleration()-expected)<1e-10,"Partial traction expanded to a full tick");
            require(Math.abs(train.acceleration()-expected)<1e-10,"Prediction consumed traction");
            train.approachTargetSpeed(4);
            require(Math.abs(train.speed-expected)<1e-10,"Create exceeded accepted impulse with a control modifier");
            train.approachTargetSpeed(1);
            require(Math.abs(train.speed-expected)<1e-10,"Create replayed accepted traction");
            boundary.electromagnetism$traction(session,0,0.001,1000,level.getServer().getTickCount());
            train.approachTargetSpeed(1);
            require(Math.abs(train.speed-expected)<1e-10,"Duplicate publication replayed traction");
            train.speed=0;train.targetSpeed=-1;
            boundary.electromagnetism$traction(session,0.001,0.002,-1000,level.getServer().getTickCount());
            train.approachTargetSpeed(4);
            require(Math.abs(train.speed+expected)<1e-10,"Reverse traction lost direction");
            boundary.electromagnetism$tractionUnavailable();train.targetSpeed=1;
            train.approachTargetSpeed(1);
            require(Math.abs(train.speed)<1e-10,"Braking failed to stop at zero without reverse traction");
            boundary.electromagnetism$traction(session,0.002,0.003,1000,level.getServer().getTickCount()-2L);
            require(train.acceleration()==0,"Stale traction survived until a later control call");
        } finally {com.simibubi.create.Create.RAILWAYS.trains.remove(train.id);}
        System.out.println("NATIVE_ACCEPTED_TRAIN_TRACTION_PASSED: partial impulse, modifier bound, duplicate calls and reverse braking");
    }
    private static void verifySf6Breaker(net.minecraft.server.level.ServerLevel level,DevicesSavedData devices) {
        var a=new BlockPos(10,250,8);var b=new BlockPos(12,250,8);var c=new BlockPos(14,250,8);
        var sd=InfrastructureSavedData.load(level);var access=(CeeSimulationAccess)sd.ticker;
        try {
            for(var pos:java.util.List.of(a,b,c))level.getChunkAt(pos);
            var state=com.george_vi.electroenergetics.CEEBlocks.SF6_BREAKER.getDefaultState().setValue(com.george_vi.electroenergetics.content.transmission_distribution.sf6_breaker.SF6BreakerBlock.BASE,true);
            level.setBlock(b,state,2);state.getBlock().setPlacedBy(level,b,state,null,com.george_vi.electroenergetics.CEEBlocks.SF6_BREAKER.asStack());
            var base=devices.getDevice(b,com.george_vi.electroenergetics.content.transmission_distribution.sf6_breaker.SF6BreakerDevice.class);
            var top=devices.getDevice(b.above(),com.george_vi.electroenergetics.content.transmission_distribution.sf6_breaker.SF6BreakerDevice.class);
            require(base!=null && top!=null && base.base && !top.base,"SF6 complementary world owners missing");
            var one=devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.INSULATOR.get(),b.below(),new CompoundTag());
            var two=devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.INSULATOR.get(),b.below(2),new CompoundTag());
            var sourceTag=new CompoundTag();sourceTag.putDouble("Voltage",10);
            var source=devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_BATTERY.get(),a,sourceTag);
            var loadTag=new CompoundTag();loadTag.putDouble("Resistance",5);
            var load=devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_RESISTOR.get(),c,loadTag);
            sd.registerOrUpdateNodes(a,java.util.List.of(0,1));sd.registerOrUpdateNodes(c,java.util.List.of(0,1));
            sd.registerOrUpdateNodes(b,java.util.List.of(0));sd.registerOrUpdateNodes(b.above(),java.util.List.of(0));
            var positions=java.util.List.of(a,b,b.above(),c);double wireResistance=0;
            for(var link:new int[][]{{0,0,1,0},{2,0,3,0},{3,1,0,1}}) {
                var wire=com.george_vi.electroenergetics.simulation.infrastructure.WireData.ofLength(com.george_vi.electroenergetics.CEEWireTypes.STANDARD.get(),2);wireResistance+=wire.getResistance();
                sd.connect(new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(link[1],positions.get(link[0])),new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(link[3],positions.get(link[2])),wire);
            }
            var identity=((CeeDeviceIdentity)base).electromagnetism$nativeIdentity();
            for(int mode=0;mode<5;mode++) {
                base.updateRedstoneInput(mode==1?15:0,net.minecraft.core.Direction.NORTH);
                top.updateRedstoneInput(mode==2?15:0,net.minecraft.core.Direction.NORTH);
                one.updateRedstoneInput(mode==3?15:0,net.minecraft.core.Direction.NORTH);
                two.updateRedstoneInput(mode==4?15:0,net.minecraft.core.Direction.NORTH);
                base.cooldown=Integer.MAX_VALUE;base.closed=mode!=0; // Obsolete preTick state must not drive physics.
                for(int n=0;n<3;n++){stepWorld(sd,access);}
                double expected=10/(5.001+wireResistance+(mode==0?0.01:1e12));
                var reading=access.electromagnetism$reading(CeeNativeWorld.branchId(load,"main")).orElseThrow();
                require(Math.abs(reading.meanCurrent()-expected)<Math.max(1e-9,expected*1e-7),"SF6 redstone boundary current incorrect");
                require(base.closed==(mode==0) && top.closed==base.closed,"SF6 feedback was not applied at acceptance");
                require(identity.equals(((CeeDeviceIdentity)base).electromagnetism$nativeIdentity()) && base.cooldown==Integer.MAX_VALUE,"Legacy SF6 preTick advanced or identity changed");
            }
            two.updateRedstoneInput(0,net.minecraft.core.Direction.NORTH);source.voltage=1000;
            double start=access.electromagnetism$saveNative().getDouble("AcceptedTime"),now=start;
            double current=1000/(5.011+wireResistance),rating=com.electromagnetism.config.ModConfigs.server().nativeSwitchRatedAmperes.getF();
            double expectedTrip=-Math.log(1-Math.pow(rating/current,2));
            for(int n=0;n<500 && devices.getDevice(b)!=null;n++){now=stepWorld(sd,access).getDouble("AcceptedTime");}
            require(devices.getDevice(b)==null && devices.getDevice(b.above())==null && Math.abs(now-start-expectedTrip)<0.004,"SF6 thermal boundary failed to remove both halves: "+(now-start)+" vs "+expectedTrip);
        } finally {for(var pos:java.util.List.of(a,b,b.above(),b.below(),b.below(2),c))devices.removeDevice(pos);level.removeBlock(b,false);level.removeBlock(b.above(),false);}
        System.out.println("NATIVE_WORLD_SF6_PASSED: complementary halves, four redstone command routes, finite contact losses, accepted feedback and analytic thermal destruction");
    }

    private static void verifyVoltageRequest(net.minecraft.server.level.ServerLevel level,DevicesSavedData devices) {
        var ticker=InfrastructureSavedData.load(level).ticker;
        require(ticker.lastResults==null,"Voltage request regression must exercise absent legacy results");
        var player=new net.neoforged.neoforge.common.util.FakePlayer(level,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"NativeVoltage")) {
            net.minecraft.network.chat.Component last;
            @Override public void displayClientMessage(net.minecraft.network.chat.Component message,boolean actionBar){last=message;}
        };
        new RequestVoltageDataPacket(new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(0,new BlockPos(10,250,8))).handle(player);
        require(player.last!=null && player.last.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text
                && text.getKey().equals("electromagnetism.measurement.node_voltage_unavailable"),"Legacy voltage request did not report unavailable");
        require(ticker.lastResults==null,"Voltage request fabricated legacy results");
        var sd=InfrastructureSavedData.load(level);
        var position=new BlockPos(10,250,8);level.getChunkAt(position);
        sd.registerOrUpdateNodes(position,java.util.List.of(0,1,2));
        player.setPos(10,250,8);
        var id=new com.electromagnetism.simulation.SimulationProtocol.DeviceId(UUID.randomUUID(),0);
        var positive=new com.electromagnetism.simulation.SimulationProtocol.PortId(id,0);
        var negative=new com.electromagnetism.simulation.SimulationProtocol.PortId(id,1);
        var isolated=new com.electromagnetism.simulation.SimulationProtocol.PortId(id,2);
        var a=new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(0,position);
        var b=new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(1,position);
        var c=new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(2,position);
        var ports=java.util.Map.of(a,positive,b,negative,c,isolated);
        var probes=new NativeVoltageProbes(uuid->uuid.equals(player.getUUID())?player:null);
        try {
            player.setShiftKeyDown(true);probes.select(sd,player,b,ports);player.setShiftKeyDown(false);
            probes.select(sd,player,a,ports);
            var channels=new java.util.ArrayList<com.electromagnetism.simulation.SimulationProtocol.WaveformChannel>();
            var branch=new com.electromagnetism.simulation.SimulationProtocol.Branch(UUID.randomUUID(),positive,negative,"resistor",java.util.Map.of("resistanceOhms",5.0));
            probes.subscribe(sd,ports,java.util.List.of(branch),channels);
            require(channels.size()==1 && channels.getFirst().negative().equals(negative),"Probe lost explicit reference");
            probes.publish(sd,new com.electromagnetism.simulation.SimulationProtocol.Waveform(channels.getFirst().channel(),positive,negative,java.util.List.of(new com.electromagnetism.simulation.SimulationProtocol.WaveformSample(0.05,java.util.OptionalDouble.of(10),com.electromagnetism.simulation.SimulationProtocol.SampleQuality.VALID))));
            require(player.last.getString().contains("10") && player.last.getString().contains("V"),"Probe did not publish accepted voltage sample");
            probes.select(sd,player,c,ports);channels.clear();probes.subscribe(sd,ports,java.util.List.of(branch),channels);
            require(channels.isEmpty(),"Probe invented cross-island reference");
            probes.select(sd,player,a,ports);
            var replacement=new com.electromagnetism.simulation.SimulationProtocol.PortId(new com.electromagnetism.simulation.SimulationProtocol.DeviceId(UUID.randomUUID(),0),0);
            probes.subscribe(sd,java.util.Map.of(a,replacement,b,negative,c,isolated),java.util.List.of(branch),channels);
            require(channels.isEmpty(),"Replaced probe instance retained subscription");
        } finally {sd.registerOrUpdateNodes(position,java.util.List.of());}
    }

    private static void verifyClampMeter(net.minecraft.server.level.ServerLevel level,DevicesSavedData devices) {
        var a=new BlockPos(10,250,8);var b=new BlockPos(12,250,8);
        var sd=InfrastructureSavedData.load(level);var access=(CeeSimulationAccess)sd.ticker;
        var player=new net.neoforged.neoforge.common.util.FakePlayer(level,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"NativeClamp")) {
            net.minecraft.network.chat.Component last;
            @Override public void displayClientMessage(net.minecraft.network.chat.Component message,boolean actionBar){last=message;}
        };
        try {
            level.getChunkAt(a);level.getChunkAt(b);
            var sourceTag=new CompoundTag();sourceTag.putDouble("Voltage",10);
            var source=devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_BATTERY.get(),a,sourceTag);
            var loadTag=new CompoundTag();loadTag.putDouble("Resistance",5);
            devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_RESISTOR.get(),b,loadTag);
            sd.registerOrUpdateNodes(a,java.util.List.of(0,1));sd.registerOrUpdateNodes(b,java.util.List.of(0,1));
            var connection=new com.george_vi.electroenergetics.foundation.nodes.InWorldNodeConnection(new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(0,a),new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(0,b));
            double resistance=0;
            for(int pin=0;pin<2;pin++) {
                var wire=com.george_vi.electroenergetics.simulation.infrastructure.WireData.ofLength(com.george_vi.electroenergetics.CEEWireTypes.STANDARD.get(),2);resistance+=wire.getResistance();
                sd.connect(new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(pin,a),new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(pin,b),wire);
            }
            var meter=com.george_vi.electroenergetics.CEEItems.CLAMP_METER.asStack();
            meter.set(com.george_vi.electroenergetics.CEEDataComponents.NODE_CONNECTION,connection);
            require(NativeClampMeter.read(level,connection).isEmpty(),"Clamp invented a reading before acceptance");
            for(int n=0;n<5;n++){stepWorld(sd,access);}
            double expected=10/(5.001+resistance);
            require(Math.abs(NativeClampMeter.read(level,connection).orElseThrow().rmsCurrent()-expected)<1e-8,"Clamp disagrees with native series current");
            meter.getItem().onUseTick(level,player,meter,100);
            require(player.last!=null && !player.last.getString().contains("--") && player.last.getString().contains("A"),"Clamp item did not publish accepted RMS");
            var stick=com.george_vi.electroenergetics.CEEItems.LINEMANS_STICK.asStack();
            stick.set(com.george_vi.electroenergetics.CEEDataComponents.NODE_CONNECTION,connection);
            player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND,meter);player.last=null;
            stick.getItem().onUseTick(level,player,stick,100);
            require(player.last!=null && !player.last.getString().contains("--"),"Stick-assisted clamp bypassed native reading");
            double epoch=access.electromagnetism$saveNative().getDouble("AcceptedTime"),now=epoch;
            source.acFrequency=50;
            for(int n=0;n<100 && now<epoch+0.1;n++) {
                now=stepWorld(sd,access).getDouble("AcceptedTime");
            }
            require(now>=epoch+0.1,"Clamp AC reference window incomplete");
            var ac=NativeClampMeter.read(level,connection).orElseThrow();
            double w=2*Math.PI*50,dt=ac.windowEnd()-ac.windowStart();
            double meanSquare=0.5+(Math.sin(2*w*(ac.windowEnd()-epoch))-Math.sin(2*w*(ac.windowStart()-epoch)))/(4*w*dt);
            require(Math.abs(ac.rmsCurrent()/expected-Math.sqrt(meanSquare))<0.003,"Clamp RMS disagrees with analytic AC window");
            var old=((CeeWireIdentity)sd.getConnectionData(connection)).electromagnetism$wireIdentity();
            sd.removeConnection(connection);
            require(NativeClampMeter.read(level,connection).isEmpty(),"Removed wire retained clamp reading");
            sd.connect(connection.node1(),connection.node2(),com.george_vi.electroenergetics.simulation.infrastructure.WireData.ofLength(com.george_vi.electroenergetics.CEEWireTypes.STANDARD.get(),2));
            require(!old.equals(((CeeWireIdentity)sd.getConnectionData(connection)).electromagnetism$wireIdentity()) && NativeClampMeter.read(level,connection).isEmpty(),"Replacement wire inherited stale clamp current");
            meter.getItem().onUseTick(level,player,meter,100);
            require(player.last.getString().contains("--"),"Unavailable clamp displayed valid zero");
        } finally {devices.removeDevice(a);devices.removeDevice(b);}
        System.out.println("NATIVE_WORLD_CLAMP_PASSED: accepted DC/analytic AC conductor RMS, actual clamp/stick item paths, unavailable and replacement identity");
    }

    private static void verifyFrequencyMeter(net.minecraft.server.level.ServerLevel level,DevicesSavedData devices) {
        var a=new BlockPos(10,250,8);var b=new BlockPos(12,250,8);
        var sd=InfrastructureSavedData.load(level);var access=(CeeSimulationAccess)sd.ticker;
        try {
            level.getChunkAt(a);level.getChunkAt(b);
            level.setBlockAndUpdate(b,com.george_vi.electroenergetics.CEEBlocks.FREQUENCY_METER.getDefaultState());
            var meter=devices.getDevice(b,com.george_vi.electroenergetics.content.frequency_meter.FrequencyMeterDevice.class);
            require(meter!=null,"Frequency meter world owner missing");meter.ticks=Integer.MAX_VALUE;meter.prevPeriod=1e-12;
            var be=(com.george_vi.electroenergetics.content.frequency_meter.FrequencyMeterBlockEntity)level.getBlockEntity(b);
            require(!be.getUpdateTag(level.registryAccess()).getBoolean("NativeAvailable"),"Uninitialized frequency displayed as valid zero");
            var sourceTag=new CompoundTag();sourceTag.putDouble("Voltage",35);sourceTag.putDouble("ACFrequency",10);
            var source=devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_BATTERY.get(),a,sourceTag);
            sd.registerOrUpdateNodes(a,java.util.List.of(0,1));sd.registerOrUpdateNodes(b,java.util.List.of(0,1));
            for(int pin=0;pin<2;pin++)sd.connect(new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(pin,a),new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(pin,b),com.george_vi.electroenergetics.simulation.infrastructure.WireData.ofLength(com.george_vi.electroenergetics.CEEWireTypes.STANDARD.get(),2));
            for(double frequency:new double[]{10,50,0}) {
                source.acFrequency=frequency;
                double start=access.electromagnetism$saveNative().getDouble("AcceptedTime"),now=start;
                for(int n=0;n<500 && now<start+0.5;n++) {
                    now=stepWorld(sd,access).getDouble("AcceptedTime");
                }
                require(now>=start+0.5,"Frequency meter failed to acquire physical time: "+access.electromagnetism$nativeStatus());
                var packet=be.getUpdateTag(level.registryAccess());
                var reading=access.electromagnetism$reading(CeeNativeWorld.branchId(meter,"main")).orElseThrow();
                if(frequency>0)require(packet.getBoolean("NativeAvailable") && Math.abs(packet.getFloat("Frequency")-frequency)<0.01 && Math.abs(reading.frequency().orElseThrow()-frequency)<0.01,"Frequency meter did not consume native measured frequency");
                else require(!packet.getBoolean("NativeAvailable") && Float.isNaN(packet.getFloat("Frequency")) && reading.frequency().isEmpty(),"DC was displayed as a valid zero-hertz measurement");
            }
            devices.removeDevice(a);sd.ticker.endTick();
            require(!be.getUpdateTag(level.registryAccess()).getBoolean("NativeAvailable"),"Removed frequency source left valid display");
        } finally {devices.removeDevice(a);devices.removeDevice(b);level.removeBlock(b,false);}
        System.out.println("NATIVE_WORLD_FREQUENCY_PASSED: measured 10/50 Hz, retuning, ignored legacy counters, DC applicability and unavailable display");
    }

    private static void verifySynchroscope(net.minecraft.server.level.ServerLevel level,DevicesSavedData devices) {
        var sd=InfrastructureSavedData.load(level);var access=(CeeSimulationAccess)sd.ticker;
        var b=new BlockPos(8,250,6);var sources=new java.util.ArrayList<com.george_vi.electroenergetics.content.creative_battery.CreativeBatteryDevice>();
        var positions=java.util.stream.IntStream.range(0,6).mapToObj(n->new BlockPos(2+2*n,250,2)).toList();
        try {
            level.getChunkAt(b);level.setBlockAndUpdate(b,com.george_vi.electroenergetics.CEEBlocks.SYNCHROSCOPE.getDefaultState());
            var device=devices.getDevice(b);require(device!=null,"Synchroscope placement missing owner");sd.registerOrUpdateNodes(b,java.util.List.of(0,1,2,3,4,5));
            var be=(com.george_vi.electroenergetics.content.synchroscope.SynchroscopeBlockEntity)level.getBlockEntity(b);
            for(int phase=0;phase<6;phase++) {
                var pos=positions.get(phase);level.getChunkAt(pos);
                var tag=new CompoundTag();tag.putDouble("Voltage",new double[]{35,24,42}[phase%3]);tag.putDouble("ACFrequency",50);tag.putFloat("PhaseOffset",-120*(phase%3)+(phase>=3?30:0));
                sources.add(devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_BATTERY.get(),pos,tag));
                sd.registerOrUpdateNodes(pos,java.util.List.of(0,1));
                sd.connect(new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(0,pos),new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(phase,b),com.george_vi.electroenergetics.simulation.infrastructure.WireData.ofLength(com.george_vi.electroenergetics.CEEWireTypes.STANDARD.get(),2));
                if(phase%3!=0)sd.connect(new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(1,pos),new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(1,positions.get(phase/3*3)),com.george_vi.electroenergetics.simulation.infrastructure.WireData.ofLength(com.george_vi.electroenergetics.CEEWireTypes.STANDARD.get(),2));
            }
            for(int mode=0;mode<3;mode++) {
                if(mode==1)for(int phase=3;phase<6;phase++)sources.get(phase).acFrequency=51;
                if(mode==2)for(int phase=0;phase<6;phase++) {
                    sources.get(phase).acFrequency=50;sources.get(phase).phaseOffset=360+(phase>=3?120:-120)*(phase%3)+(phase>=3?30:0);
                }
                double start=access.electromagnetism$saveNative().getDouble("AcceptedTime"),now=start;
                for(int n=0;n<500 && now<start+0.2;n++){now=stepWorld(sd,access).getDouble("AcceptedTime");}
                require(now>=start+0.2,"Synchroscope did not advance accepted time: "+access.electromagnetism$nativeStatus());
                var packet=be.getUpdateTag(level.registryAccess());
                require(packet.getBoolean("NativeAvailable"),"Synchroscope failed to acquire six native line phases");
                if(mode<2) {
                    double expected=30+(mode==1?360*(now-start):0),difference=Math.toRadians(packet.getFloat("PhaseOffset")-expected);
                    require(packet.getBoolean("ValidConnection") && Math.abs(Math.toDegrees(Math.atan2(Math.sin(difference),Math.cos(difference))))<1,"Synchroscope phase/slip is inconsistent with accepted time: "+packet);
                } else require(!packet.getBoolean("ValidConnection"),"Reversed phase order was marked compatible");
            }
            sd.removeConnection(new com.george_vi.electroenergetics.foundation.nodes.InWorldNodeConnection(new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(0,positions.get(5)),new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(5,b)));
            sd.ticker.endTick();require(!be.getUpdateTag(level.registryAccess()).getBoolean("NativeAvailable"),"Obsolete synchroscope reading survived a missing phase");
            for(int n=0;n<12;n++){stepWorld(sd,access);}
            require(!be.getUpdateTag(level.registryAccess()).getBoolean("NativeAvailable"),"Missing phase generated a valid phase-order measurement");
        } finally {for(var pos:positions)devices.removeDevice(pos);devices.removeDevice(b);level.removeBlock(b,false);}
        System.out.println("NATIVE_WORLD_SYNCHROSCOPE_PASSED: unbalanced three-phase groups, 30-degree offset, 1-Hz slip on accepted time, reversed order and missing phase");
    }

    private static void verifyGauges(net.minecraft.server.level.ServerLevel level,DevicesSavedData devices) {
        var a=new BlockPos(10,250,8);var b=new BlockPos(12,250,8);var c=new BlockPos(14,250,8);
        var sd=InfrastructureSavedData.load(level);var access=(CeeSimulationAccess)sd.ticker;
        for(boolean voltmeter:new boolean[]{true,false}) {
            try {
                var tag=new CompoundTag();tag.putDouble("Voltage",10);
                var resistorTag=new CompoundTag();resistorTag.putDouble("Resistance",5);
                devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_BATTERY.get(),a,tag);
                devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_RESISTOR.get(),c,resistorTag);
                level.setBlockAndUpdate(b,voltmeter?com.george_vi.electroenergetics.CEEBlocks.VOLTMETER.getDefaultState():com.george_vi.electroenergetics.CEEBlocks.AMMETER.getDefaultState());
                if(devices.getDevice(b)==null)devices.addDevice(voltmeter?com.george_vi.electroenergetics.CEESimulatedDevices.VOLTMETER.get():com.george_vi.electroenergetics.CEESimulatedDevices.AMMETER.get(),b,new CompoundTag());
                var gauge=(com.george_vi.electroenergetics.content.gauge.ElectricGaugeBlockEntity)level.getBlockEntity(b);
                require(Double.isNaN(gauge.voltage),"New gauge displayed valid zero before native acceptance");
                for(var pos:java.util.List.of(a,b,c))sd.registerOrUpdateNodes(pos,java.util.List.of(0,1));
                var positions=java.util.List.of(a,b,c);double wireResistance=0;
                for(var link:new int[][]{{0,0,1,0},{1,1,2,0},{2,1,0,1}}) {
                    var wire=com.george_vi.electroenergetics.simulation.infrastructure.WireData.ofLength(com.george_vi.electroenergetics.CEEWireTypes.STANDARD.get(),2);wireResistance+=wire.getResistance();
                    sd.connect(new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(link[1],positions.get(link[0])),new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(link[3],positions.get(link[2])),wire);
                }
                for(int attempt=0;attempt<30 && !Double.isFinite(gauge.voltage);attempt++){stepWorld(sd,access);}
                double current=10/(5+0.001+wireResistance+(voltmeter?1e6:0.01));
                double expected=voltmeter?current*1e6:current;
                require(Math.abs(com.electromagnetism.integration.cee.display.GaugeReading.measurement(gauge)-expected)<1e-7,"Native gauge did not consume accepted SI RMS");
                ((NativeGauge)gauge).electromagnetism$gaugeUnavailable();
                require(Double.isNaN(gauge.voltage) && gauge.dialTarget==0 && gauge.redstoneSignal==0,"Unavailable gauge retained physical reading/output");
            } finally {for(var pos:java.util.List.of(a,b,c))devices.removeDevice(pos);level.removeBlock(b,false);}
        }
        System.out.println("NATIVE_WORLD_GAUGES_PASSED: voltmeter/ammeter accepted RMS, SI range consumers and explicit unavailable state");
    }

    private static void verifyGeneratorScope(net.minecraft.server.level.ServerLevel level,DevicesSavedData devices) {
        var a=new BlockPos(10,250,8);var b=new BlockPos(12,250,8);
        var sd=InfrastructureSavedData.load(level);var access=(CeeSimulationAccess)sd.ticker;
        try {
            level.setBlockAndUpdate(a,com.electromagnetism.registry.ModBlocks.SIGNAL_GENERATOR.getDefaultState());
            level.setBlockAndUpdate(b,com.electromagnetism.registry.ModBlocks.OSCILLOSCOPE.getDefaultState());
            if(devices.getDevice(a)==null)devices.addDevice(ModDeviceTypes.SIGNAL_GENERATOR.get(),a,new CompoundTag());
            if(devices.getDevice(b)==null)devices.addDevice(ModDeviceTypes.OSCILLOSCOPE.get(),b,new CompoundTag());
            var generator=(com.electromagnetism.content.instruments.SignalGeneratorBlockEntity)level.getBlockEntity(a);
            var scope=(com.electromagnetism.content.instruments.InstrumentBlockEntity)level.getBlockEntity(b);
            generator.adjust(7);
            sd.registerOrUpdateNodes(a,java.util.List.of(0,1));sd.registerOrUpdateNodes(b,java.util.List.of(0,1));
            for(int terminal=0;terminal<2;terminal++)sd.connect(new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(terminal,a),new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(terminal,b),com.george_vi.electroenergetics.simulation.infrastructure.WireData.ofLength(com.george_vi.electroenergetics.CEEWireTypes.STANDARD.get(),2));
            for(int wave=0;wave<4;wave++) {
                if(wave>0)generator.adjust(0);
                double origin=access.electromagnetism$saveNative().getDouble("AcceptedTime"),end=origin;
                for(int attempt=0;attempt<200 && end<origin+0.08;attempt++) {
                    end=stepWorld(sd,access).getDouble("AcceptedTime");
                }
                require(end>=origin+0.08,"Generator/scope did not advance: "+wave+" "+access.electromagnetism$nativeStatus());
                require(!generator.samples().isEmpty(),"Generator output monitor has no accepted waveform");
                var samples=scope.samples();require(samples.size()>32,"Scope has no native waveform history");
                double last=-1;
                for(var sample:samples) {require(sample.timeSeconds()>last && sample.value().isPresent(),"Invalid scope sample ordering/value");last=sample.timeSeconds();}
                var sample=samples.getLast();
                require(sample.quality()==com.electromagnetism.core.signal.WaveformBuffer.Quality.RECONSTRUCTED,"Native filtered sample quality lost");
                double phase=2*Math.PI*2*(sample.timeSeconds()-origin),expected=wave==3?1:0;
                double pole=256/Math.sqrt(Math.pow(2,0.25)-1);
                for(int harmonic=1;wave<3 && harmonic<=(wave==0?1:15);harmonic+=2) {
                    double weight=wave==0?1:wave==1?4/(Math.PI*harmonic)*(1-harmonic/16.0):8/(Math.PI*Math.PI*harmonic*harmonic)*(((harmonic-1)/2)%2==0?1:-1);
                    double ratio=2*harmonic/pole;
                    if(wave==1)weight/=0.9603637867004527;else if(wave==2)weight/=0.974702508054903;
                    expected+=weight*Math.sin(harmonic*phase-4*Math.atan(ratio))/Math.pow(1+ratio*ratio,2);
                }
                require(Math.abs(sample.value().orElseThrow()-expected)<0.025,"Native generated/filter waveform differs from independent Fourier/transfer reference: "+wave+" "+sample.value()+" vs "+expected);
            }
            devices.removeDevice(a);level.removeBlock(a,false);sd.ticker.tick();
            require(scope.samples().isEmpty(),"Scope retained valid-looking obsolete topology history");
            drainWorld(sd,access);
        } finally {devices.removeDevice(a);devices.removeDevice(b);level.removeBlock(a,false);level.removeBlock(b,false);}
        System.out.println("NATIVE_WORLD_SCOPE_PASSED: sine, finite-band square/triangle, DC and accepted filtered waveform publication");
    }

    private static void verifyStorageTransients(net.minecraft.server.level.ServerLevel level,DevicesSavedData devices) {
        var a=new BlockPos(10,250,8);var b=new BlockPos(12,250,8);
        var sd=InfrastructureSavedData.load(level);var access=(CeeSimulationAccess)sd.ticker;
        for(int kind=0;kind<3;kind++) {
            boolean capacitor=kind!=1;
            var terminalPosition=kind==2?b.east():b;
            try {
                var sourceTag=new CompoundTag();sourceTag.putDouble("Voltage",1);
                var storageTag=new CompoundTag();storageTag.putDouble("Capacitance",0.1);storageTag.putDouble("Inductance",0.1);
                storageTag.putString("Facing","east");
                storageTag.putDouble("LastVoltage",999);storageTag.putDouble("LastCurrent",999);storageTag.putFloat("Temp",9999);
                devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_BATTERY.get(),a,sourceTag);
                SimulatedDevice storage=kind==2?devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.HV_CAPACITOR.get(),b,storageTag):capacitor?devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CAPACITOR.get(),b,storageTag):devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.INDUCTOR.get(),b,storageTag);
                if(kind==2)devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.DOUBLE_CONNECTOR.get(),terminalPosition,new CompoundTag());
                sd.registerOrUpdateNodes(a,java.util.List.of(0,1));sd.registerOrUpdateNodes(terminalPosition,java.util.List.of(0,1));
                double resistance=0.101;
                for(int terminal=0;terminal<2;terminal++) {
                    var wire=com.george_vi.electroenergetics.simulation.infrastructure.WireData.ofLength(com.george_vi.electroenergetics.CEEWireTypes.STANDARD.get(),2);
                    resistance+=wire.getResistance();
                    sd.connect(new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(terminal,a),new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(terminal,terminalPosition),wire);
                }
                double origin=access.electromagnetism$saveNative().getDouble("AcceptedTime");
                var branch=CeeNativeWorld.branchId(storage,"main");
                for(int attempt=0;attempt<50;attempt++) {
                    stepWorld(sd,access);
                    if(access.electromagnetism$reading(branch).isPresent())break;
                }
                var observed=access.electromagnetism$reading(branch);
                require(observed.isPresent(),"Storage transient unavailable: "+access.electromagnetism$nativeStatus());
                var reading=observed.orElseThrow();
                double tau=capacitor?0.1/(1/resistance+1e-9):0.1*(1/resistance+1e-9);
                double left=reading.windowStart()-origin,right=reading.windowEnd()-origin;
                double decay=tau*(Math.exp(-left/tau)-Math.exp(-right/tau))/(right-left);
                double expected=capacitor?(1/(1+resistance*1e-9))*(1-decay):(1/resistance)*(1-decay);
                double actual=capacitor?reading.meanVoltage():reading.meanCurrent();
                require(Math.abs(actual-expected)<Math.max(1e-5,Math.abs(expected)*0.005),"Storage transient differs from independent RC/RL solution: "+capacitor+" "+actual+" vs "+expected);
            } finally {devices.removeDevice(a);devices.removeDevice(b);if(kind==2)devices.removeDevice(terminalPosition);}
        }
        System.out.println("NATIVE_WORLD_STORAGE_PASSED: RC/RL and adjacent-connector HV capacitor accepted-window analytic means and no legacy charge/temperature import");
    }

    private static void verifyDiodeAndEarth(net.minecraft.server.level.ServerLevel level,DevicesSavedData devices) {
        var a=new BlockPos(10,250,8);var b=new BlockPos(12,250,8);var c=new BlockPos(14,250,8);var d=new BlockPos(14,250,10);
        var sd=InfrastructureSavedData.load(level);var access=(CeeSimulationAccess)sd.ticker;
        try {
            var sourceTag=new CompoundTag();sourceTag.putDouble("Voltage",0.3);
            var source=devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_BATTERY.get(),a,sourceTag);
            var diode=devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.DIODE.get(),b,new CompoundTag());
            devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.GROUND_ROD.get(),c,new CompoundTag());
            devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.GROUND_ROD.get(),d,new CompoundTag());
            sd.registerOrUpdateNodes(a,java.util.List.of(0,1));sd.registerOrUpdateNodes(b,java.util.List.of(0,1));sd.registerOrUpdateNodes(c,java.util.List.of(0));sd.registerOrUpdateNodes(d,java.util.List.of(0));
            var positions=java.util.List.of(a,b,c,d);
            double resistance=2.001;
            for(var link:new int[][]{{0,0,1,1},{0,1,2,0},{1,0,3,0}}) {
                var wire=com.george_vi.electroenergetics.simulation.infrastructure.WireData.ofLength(com.george_vi.electroenergetics.CEEWireTypes.STANDARD.get(),2);
                resistance+=wire.getResistance();
                sd.connect(new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(link[1],positions.get(link[0])),new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(link[3],positions.get(link[2])),wire);
            }
            for(double voltage:new double[]{0.3,-0.3}) {
                source.voltage=voltage;
                var branch=CeeNativeWorld.branchId(diode,"main");
                for(int attempt=0;attempt<30;attempt++) {
                    stepWorld(sd,access);
                    if(attempt>=2 && access.electromagnetism$reading(branch).isPresent())break;
                }
                var observed=access.electromagnetism$reading(branch);
                require(observed.isPresent(),"Diode/earth loop has no native reading: "+access.electromagnetism$nativeStatus());
                double low=-1e-9,high=0.001;
                for(int j=0;j<100;j++) {double mid=(low+high)/2;double residual=mid-1e-9*Math.expm1((voltage-mid*resistance)/0.05);if(residual>0)high=mid;else low=mid;}
                require(Math.abs(observed.orElseThrow().meanCurrent()-(low+high)/2)<1e-10,"Diode polarity or finite earth conductance mismatch: "+voltage+" "+observed);
            }
        } finally {for(var pos:java.util.List.of(a,b,c,d))devices.removeDevice(pos);}
        System.out.println("NATIVE_WORLD_DIODE_EARTH_PASSED: forward/reverse Shockley current through two finite earth contacts");
    }

    private static void verifyTerminalAndReversingSwitch(net.minecraft.server.level.ServerLevel level,DevicesSavedData devices) {
        var a=new BlockPos(10,250,8);var b=new BlockPos(12,250,8);var c=new BlockPos(14,250,8);var d=new BlockPos(14,250,10);
        var sd=InfrastructureSavedData.load(level);var access=(CeeSimulationAccess)sd.ticker;
        try {
            var sourceTag=new CompoundTag();sourceTag.putDouble("Voltage",10);
            var loadTag=new CompoundTag();loadTag.putDouble("Resistance",5);
            devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_BATTERY.get(),a,sourceTag);
            var load=devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_RESISTOR.get(),b,loadTag);
            level.setBlockAndUpdate(c,com.electromagnetism.registry.ModBlocks.REVERSING_SWITCH.getDefaultState());
            if(devices.getDevice(c)==null)devices.addDevice(ModDeviceTypes.REVERSING_SWITCH.get(),c,new CompoundTag());
            devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.DOUBLE_CONNECTOR.get(),d,new CompoundTag());
            sd.registerOrUpdateNodes(a,java.util.List.of(0,1));sd.registerOrUpdateNodes(b,java.util.List.of(0,1));
            sd.registerOrUpdateNodes(c,java.util.List.of(0,1,2,3));sd.registerOrUpdateNodes(d,java.util.List.of(0,1));
            var positions=java.util.List.of(a,b,c,d);
            int[][] links={{0,0,2,0},{0,1,2,1},{2,2,3,0},{2,3,3,1},{3,0,1,0},{3,1,1,1}};
            double wireResistance=0;
            for(var link:links) {
                var wire=com.george_vi.electroenergetics.simulation.infrastructure.WireData.ofLength(com.george_vi.electroenergetics.CEEWireTypes.STANDARD.get(),2);
                wireResistance+=wire.getResistance();
                sd.connect(new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(link[1],positions.get(link[0])),new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(link[3],positions.get(link[2])),wire);
            }
            double expected=10/(5+0.001+wireResistance+2*com.electromagnetism.config.ModConfigs.server().reversingSwitchResistance.getF());
            for(boolean reverse:new boolean[]{false,true,false}) {
                level.setBlockAndUpdate(c,level.getBlockState(c).setValue(com.george_vi.electroenergetics.content.cut_off_switch.CutOffSwitchBlock.CLOSED,reverse));
                var branch=CeeNativeWorld.branchId(load,"main");
                for(int attempt=0;attempt<20;attempt++) {
                    stepWorld(sd,access);
                    if(access.electromagnetism$reading(branch).isPresent())break;
                }
                var observed=access.electromagnetism$reading(branch);
                require(observed.isPresent(),"Reversing switch has no native output: "+access.electromagnetism$nativeStatus());
                require(Math.abs(observed.orElseThrow().meanCurrent()-(reverse?-expected:expected))<1e-7,"Reversing switch or separate connector terminals shorted/miswired");
            }
        } finally {for(var pos:java.util.List.of(a,b,c,d))devices.removeDevice(pos);level.removeBlock(c,false);}
        System.out.println("NATIVE_WORLD_SWITCH_PASSED: independent connector terminals and forward/reverse/forward accepted topology edits");
    }

    private static void verifyBundledWire(net.minecraft.server.level.ServerLevel level,DevicesSavedData devices) {
        var a=new BlockPos(10,250,8);var b=new BlockPos(14,250,8);var c=new BlockPos(10,250,10);var d=new BlockPos(14,250,10);var e=new BlockPos(12,250,8);var f=new BlockPos(12,250,10);
        var sd=InfrastructureSavedData.load(level);var access=(CeeSimulationAccess)sd.ticker;
        var player=net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level);
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        for(int side=0;side<4;side++)try {
            var block=com.george_vi.electroenergetics.CEEBlocks.DUPLEX_WIRE_TERMINATION.get();
            boolean roll=(side&1)!=0,flip=(side&2)!=0;
            for(var pos:java.util.List.of(a,b,c,d,e,f))level.getChunkAt(pos);
            for(var pos:java.util.List.of(c,d)) {
                var state=block.defaultBlockState().setValue(com.george_vi.electroenergetics.content.bundled_wire.BundledWireTerminationBlock.ROLL,roll).setValue(com.george_vi.electroenergetics.content.bundled_wire.BundledWireTerminationBlock.FLIP,flip);
                level.setBlockAndUpdate(pos,state);block.tick(state,level,pos,level.random);
                for(int conductor=0;conductor<=2;conductor++) {
                    var node=com.george_vi.electroenergetics.content.bundled_wire.BundledWireNodeConfigurator.getWireNodeFor(pos,roll,flip,conductor);
                    require(node.id()==4096+16*side+conductor && sd.getNodeData(node)!=null,"Bundle terminal identity disagrees with actual block geometry");
                }
            }
            var connector=com.george_vi.electroenergetics.CEEBlocks.CONNECTOR.get();level.setBlockAndUpdate(e,connector.defaultBlockState());connector.tick(level.getBlockState(e),level,e,level.random);
            var insulator=com.george_vi.electroenergetics.CEEBlocks.INSULATOR.get();level.setBlockAndUpdate(f,insulator.defaultBlockState());insulator.tick(level.getBlockState(f),level,f,level.random);
            require(sd.getNodesAt(f).isEmpty(),"Insulator incorrectly exposes conducting terminals");
            var sourceTag=new CompoundTag();sourceTag.putDouble("Voltage",10);var loadTag=new CompoundTag();loadTag.putDouble("Resistance",5);
            devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_BATTERY.get(),a,sourceTag);
            var load=devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_RESISTOR.get(),b,loadTag);
            sd.registerOrUpdateNodes(a,java.util.List.of(0,1));sd.registerOrUpdateNodes(b,java.util.List.of(0,1));
            var stack=com.george_vi.electroenergetics.CEEItems.DUPLEX_WIRE_SPOOL.asStack();stack.setCount(3);player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,stack);
            for(var pos:java.util.List.of(c,d)) {
                var result=stack.getItem().useOn(new net.minecraft.world.item.context.UseOnContext(player,net.minecraft.world.InteractionHand.MAIN_HAND,new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos),net.minecraft.core.Direction.DOWN,pos,false)));
                require(result.consumesAction(),"Bundle spool did not complete actual item interaction");
            }
            require(stack.getCount()==2 && !stack.has(com.george_vi.electroenergetics.CEEDataComponents.SELECTED_WIRE_TERMINATION),"Bundle connection did not consume exactly one spool/clear selection");
            int base=4096+side*16;
            var shell=new com.george_vi.electroenergetics.foundation.nodes.InWorldNodeConnection(new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(base,c),new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(base,d));
            require(sd.getConnectionData(shell)!=null,"Successful spool action created no world cable");
            for(var pos:java.util.List.of(c,d)) {
                var repeated=stack.getItem().useOn(new net.minecraft.world.item.context.UseOnContext(player,net.minecraft.world.InteractionHand.MAIN_HAND,new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos),net.minecraft.core.Direction.DOWN,pos,false)));
                require(pos.equals(c)?repeated.consumesAction():repeated==net.minecraft.world.InteractionResult.FAIL,"Duplicate cable command was not rejected");
            }
            require(stack.getCount()==2,"Rejected duplicate cable consumed a spool");stack.remove(com.george_vi.electroenergetics.CEEDataComponents.SELECTED_WIRE_TERMINATION);
            double resistance=2*com.george_vi.electroenergetics.CEEWireTypes.BUNDLE_CONDUCTOR.get().getResistance()*4;
            var positions=java.util.List.of(a,b,c,d,e);
            for(var link:new int[][]{{0,0,4,0},{4,0,2,base+1},{0,1,2,base+2},{3,base+1,1,0},{3,base+2,1,1}}) {
                var wire=com.george_vi.electroenergetics.simulation.infrastructure.WireData.ofLength(com.george_vi.electroenergetics.CEEWireTypes.STANDARD.get(),link[0]==0 && link[2]==4?0:2);
                resistance+=2*com.george_vi.electroenergetics.CEEWireTypes.STANDARD.get().getResistance();
                sd.connect(new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(link[1],positions.get(link[0])),new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(link[3],positions.get(link[2])),wire);
            }
            for(int n=0;n<4;n++){stepWorld(sd,access);}
            var reading=access.electromagnetism$reading(CeeNativeWorld.branchId(load,"main")).orElseThrow();
            require(Math.abs(reading.meanCurrent()-10/(5+0.001+resistance))<1e-7,"Duplex conductors/connector shorted, missing or resistance duplicated");
            var frame=new CeeNativeWorld(sd).capture();
            var shellIdentity=((CeeWireIdentity)sd.getConnectionData(shell)).electromagnetism$wireIdentity();
            require(frame.branches().stream().noneMatch(branch->branch.id().equals(shellIdentity)),"Decorative shell became an electrical branch");
            require(frame.branches().size()==10,"Duplex circuit has extra/missing branches: "+frame.branches().size());
            var ordinary=sd.getConnectionData(new com.george_vi.electroenergetics.foundation.nodes.InWorldNodeConnection(new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(0,a),new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(0,e)));
            ordinary.length=-1;sd.ticker.tick();sd.ticker.endTick();
            require(access.electromagnetism$nativeStatus().status()==Status.UNSUPPORTED && access.electromagnetism$reading(CeeNativeWorld.branchId(load,"main")).isEmpty(),"Invalid wire length retained accepted display");ordinary.length=0;
            access.electromagnetism$resumeMissingInputs();
            sd.removeConnection(shell);
            for(int n=0;n<3;n++){stepWorld(sd,access);}
            require(Math.abs(access.electromagnetism$reading(CeeNativeWorld.branchId(load,"main")).orElseThrow().meanCurrent())<1e-10,"Removing cable left a ghost conductor");
        } finally {
            for(var pos:java.util.List.of(a,b,c,d,e,f)){devices.removeDevice(pos);level.removeBlock(pos,false);}
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,net.minecraft.world.item.ItemStack.EMPTY);
        }
        System.out.println("NATIVE_WORLD_BUNDLE_PASSED: four terminal orientations, actual spool placement, two isolated strands, passive connector/insulator and atomic cable removal");
    }

    private static void verifyWireAttachments(net.minecraft.server.level.ServerLevel level,DevicesSavedData devices) {
        var a=new BlockPos(2,250,8);var b=new BlockPos(14,250,8);
        var sd=InfrastructureSavedData.load(level);var access=(CeeSimulationAccess)sd.ticker;
        try {
            level.getChunkAt(a);level.getChunkAt(b);
            var sourceTag=new CompoundTag();sourceTag.putDouble("Voltage",50);var loadTag=new CompoundTag();loadTag.putDouble("Resistance",5);
            devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_BATTERY.get(),a,sourceTag);
            devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_RESISTOR.get(),b,loadTag);
            sd.registerOrUpdateNodes(a,java.util.List.of(0,1));sd.registerOrUpdateNodes(b,java.util.List.of(0,1));
            var first=new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(0,a);var second=new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(0,b);
            var connection=new com.george_vi.electroenergetics.foundation.nodes.InWorldNodeConnection(first,second);
            var wire=com.george_vi.electroenergetics.simulation.infrastructure.WireData.ofLength(com.george_vi.electroenergetics.CEEWireTypes.STANDARD.get(),12);
            long packed=sd.connect(first,second,wire);
            sd.connect(new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(1,a),new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(1,b),com.george_vi.electroenergetics.simulation.infrastructure.WireData.ofLength(com.george_vi.electroenergetics.CEEWireTypes.STANDARD.get(),12));
            var identity=((CeeWireIdentity)wire).electromagnetism$wireIdentity();
            for(int n=0;n<4;n++){stepWorld(sd,access);}
            var reading=access.electromagnetism$reading(identity).orElseThrow();
            require(Math.abs(Math.abs(reading.meanCurrent())-50/(5.001+2*wire.getResistance()))<1e-6 && reading.temperatureKelvin().orElseThrow()>293.15,"Decorated-wire fixture failed to establish physical current/heat");
            var snapshot=access.electromagnetism$saveNative();
            var player=net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level);
            var types=java.util.List.of(com.george_vi.electroenergetics.CEEWireAttachments.EMPTY.get(),com.george_vi.electroenergetics.CEEWireAttachments.BANNER.get(),com.george_vi.electroenergetics.CEEWireAttachments.BUNTING.get(),com.george_vi.electroenergetics.CEEWireAttachments.DAMPER.get());
            var behaviour=new com.george_vi.electroenergetics.content.wire.interaction.WireInteractionBehaviour.Empty();
            for(int index=0;index<types.size();index++) {
                behaviour.attachToWire(new com.george_vi.electroenergetics.foundation.nodes.NodeConnectionPoint(first,second,0.1f+0.25f*index),level,player,net.minecraft.world.item.Items.STRING.getDefaultInstance(),new com.george_vi.electroenergetics.content.wire.WireAttachment(types.get(index)));
                var changed=sd.getConnectionData(connection);
                require(changed.attachments().size()==index+1 && ((CeeWireIdentity)changed).electromagnetism$wireIdentity().equals(identity),"Attaching decoration reset conductor incarnation");
                require(new CeeNativeWorld(sd).capture().blockers().isEmpty(),"Audited passive wire decoration blocked native physics");
                require(java.util.Arrays.equals(snapshot.getByteArray("State"),access.electromagnetism$saveNative().getByteArray("State")),"Cosmetic wire edit reset accepted thermal state or ledger");
            }
            new com.george_vi.electroenergetics.content.wire.attachments.AttachmentRemovalWireInteractionBehaviour().interactWire(new com.george_vi.electroenergetics.foundation.nodes.NodeConnectionPoint(first,second,0.35f),level,player,com.simibubi.create.AllItems.WRENCH.asStack());
            var changed=sd.getConnectionData(connection);require(changed.attachments().size()==3,"Real attachment removal failed");
            sd.setConnectionData(packed,new com.george_vi.electroenergetics.simulation.infrastructure.WireData(changed.wireType(),changed.temperature(),changed.attachments(),changed.length));
            require(((CeeWireIdentity)sd.getConnectionData(connection)).electromagnetism$wireIdentity().equals(identity),"Packed wire edit changed incarnation");
            require(java.util.Arrays.equals(snapshot.getByteArray("State"),access.electromagnetism$saveNative().getByteArray("State")),"Removing decoration changed physical state");
            var unknown=new java.util.ArrayList<>(changed.attachments());unknown.add(net.createmod.catnip.data.Pair.of(0.95f,new com.george_vi.electroenergetics.content.wire.WireAttachment(new com.george_vi.electroenergetics.content.wire.WireAttachmentType.Empty())));
            sd.setConnectionData(connection,new com.george_vi.electroenergetics.simulation.infrastructure.WireData(changed.wireType(),0,unknown,changed.length));
            sd.ticker.tick();sd.ticker.endTick();require(access.electromagnetism$nativeStatus().status()==Status.UNSUPPORTED && access.electromagnetism$reading(identity).isEmpty(),"Unknown decoration coupling was silently accepted");
            sd.setConnectionData(connection,changed);
            access.electromagnetism$resumeMissingInputs();
        } finally {devices.removeDevice(a);devices.removeDevice(b);}
        System.out.println("NATIVE_WORLD_WIRE_ATTACHMENTS_PASSED: all four registry types, actual attach/remove paths, stable hot-conductor snapshot, packed edits and unknown coupling rejection");
    }

    private static void verifySemiconductors(net.minecraft.server.level.ServerLevel level,DevicesSavedData devices) {
        var a=new BlockPos(10,250,8);var b=new BlockPos(12,250,8);var c=new BlockPos(10,250,10);
        var sd=InfrastructureSavedData.load(level);var access=(CeeSimulationAccess)sd.ticker;
        var types=java.util.List.of(ModDeviceTypes.NPN_TRANSISTOR.get(),ModDeviceTypes.PNP_TRANSISTOR.get(),ModDeviceTypes.N_MOSFET.get(),ModDeviceTypes.P_MOSFET.get());
        for(int kind=0;kind<types.size();kind++) {
            try {
                double sign=kind%2==0?1:-1;
                var sourceTag=new CompoundTag();sourceTag.putDouble("Voltage",sign*10);
                var biasTag=new CompoundTag();biasTag.putDouble("Voltage",sign*(kind<2?0.7:2.01));
                devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_BATTERY.get(),a,sourceTag);
                devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_BATTERY.get(),c,biasTag);
                var transistor=devices.addDevice(types.get(kind),b,new CompoundTag());
                sd.registerOrUpdateNodes(a,java.util.List.of(0,1));sd.registerOrUpdateNodes(c,java.util.List.of(0,1));sd.registerOrUpdateNodes(b,java.util.List.of(0,1,2));
                int[][] links={{0,1},{1,2},{0,0},{1,2}};
                for(int j=0;j<links.length;j++)sd.connect(
                        new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(links[j][0],j<2?a:c),
                        new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(links[j][1],b),
                        com.george_vi.electroenergetics.simulation.infrastructure.WireData.ofLength(com.george_vi.electroenergetics.CEEWireTypes.STANDARD.get(),2));
                UUID branch=CeeNativeWorld.branchId(transistor,"main");
                double start=access.electromagnetism$saveNative().getDouble("AcceptedTime");
                double end=start;
                for(int attempt=0;attempt<100 && end<start+0.01;attempt++) {
                    end=stepWorld(sd,access).getDouble("AcceptedTime");
                }
                require(end>=start+0.01,"Semiconductor did not advance: "+kind+" "+access.electromagnetism$nativeStatus());
                var observation=access.electromagnetism$reading(branch);
                require(observation.isPresent(),"Semiconductor has no accepted reading: "+kind+" "+access.electromagnetism$nativeStatus());
                double expected=sign*(kind<2?0.001:0.0005);
                require(Math.abs(observation.orElseThrow().meanCurrent()-expected)<Math.abs(expected)*0.01,"Semiconductor calibration/pin order mismatch: "+kind+" "+observation);
                if(transistor instanceof com.electromagnetism.content.electronics.bjt.TransistorDevice bipolar)bipolar.icMax=0.0005;
                else ((com.electromagnetism.content.electronics.mosfet.MosfetDevice)transistor).idMax=0.00025;
                for(int attempt=0;attempt<100 && devices.getDevice(b)==transistor;attempt++) {
                    stepWorld(sd,access);
                }
                require(devices.getDevice(b)!=transistor,"Semiconductor current rating did not trigger physical protection: "+kind+" "+access.electromagnetism$nativeStatus());
                require(access.electromagnetism$reading(branch).isEmpty(),"Destroyed semiconductor retained a valid reading");
            } finally {devices.removeDevice(a);devices.removeDevice(b);devices.removeDevice(c);}
        }
        System.out.println("NATIVE_WORLD_SEMICONDUCTORS_PASSED: NPN, PNP, N/P MOSFET physical pin order, calibrated current and assembly destruction");
    }

    private static void verifyIndicatorsAndBuzzer(net.minecraft.server.level.ServerLevel level,DevicesSavedData devices) {
        var a=new BlockPos(10,250,8);var b=new BlockPos(12,250,8);var c=new BlockPos(14,250,8);var sd=InfrastructureSavedData.load(level);var access=(CeeSimulationAccess)sd.ticker;
        for(boolean buzzer:new boolean[]{false,true})try {
            for(var p:java.util.List.of(a,b,c))level.getChunkAt(p);
            level.setBlockAndUpdate(b,buzzer?com.george_vi.electroenergetics.CEEBlocks.BUZZER.getDefaultState():com.george_vi.electroenergetics.CEEBlocks.INDICATOR_BULB.getDefaultState().setValue(com.george_vi.electroenergetics.content.indicator_bulb.IndicatorBulbBlock.SIDE,2));
            var device=devices.getDevice(b);require(device!=null,"Indicator/buzzer owner missing");
            if(!buzzer)((com.george_vi.electroenergetics.content.indicator_bulb.IndicatorBulbDevice)device).side=2;
            var tag=new CompoundTag();tag.putDouble("Voltage",35);var first=devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_BATTERY.get(),a,tag);
            tag.putDouble("Voltage",-70);devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_BATTERY.get(),c,tag);
            sd.registerOrUpdateNodes(a,java.util.List.of(0,1));sd.registerOrUpdateNodes(c,java.util.List.of(0,1));sd.registerOrUpdateNodes(b,buzzer?java.util.List.of(0,1):java.util.List.of(0,1,2,3));
            for(int n=0;n<(buzzer?2:4);n++)sd.connect(new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(n,b),new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(n%2,n<2?a:c),WireFixture());
            for(int n=0;n<8;n++){stepWorld(sd,access);}
            var packet=((com.simibubi.create.foundation.blockEntity.SmartBlockEntity)level.getBlockEntity(b)).getUpdateTag(level.registryAccess());
            require(packet.getBoolean("NativeAvailable"),"Indicator/buzzer lacks accepted output");
            if(buzzer)require(Math.abs(packet.getDouble("Voltage")-35)<0.01,"Buzzer did not consume accepted RMS");
            else require(Math.abs(packet.getFloat("FirstLight")-0.5)<0.01 && packet.getFloat("SecondLight")>0.99,"Isolated indicator ports or polarity incorrect");
            devices.removeDevice(a);sd.ticker.endTick();
            packet=((com.simibubi.create.foundation.blockEntity.SmartBlockEntity)level.getBlockEntity(b)).getUpdateTag(level.registryAccess());
            require(!packet.getBoolean("NativeAvailable"),"Invalid indicator/buzzer retained valid display");
            if(buzzer)require(packet.getDouble("Voltage")==0,"Unavailable buzzer remains audible");
            else require(packet.getFloat("FirstLight")==0 && packet.getFloat("SecondLight")==0,"Unavailable indicator remains lit");
        } finally {devices.removeDevice(a);devices.removeDevice(b);devices.removeDevice(c);level.removeBlock(b,false);}
        System.out.println("NATIVE_WORLD_INDICATOR_BUZZER_PASSED: isolated dual indicator ports, polarity, RMS sound and unavailable feedback");
    }

    private static void verifyCeeBulb(net.minecraft.server.level.ServerLevel level,DevicesSavedData devices) {
        var a=new BlockPos(10,250,8);var b=new BlockPos(12,250,8);var sd=InfrastructureSavedData.load(level);var access=(CeeSimulationAccess)sd.ticker;
        try {
            level.getChunkAt(a);level.getChunkAt(b);level.setBlockAndUpdate(b,com.george_vi.electroenergetics.CEEBlocks.BULB.getDefaultState());
            var bulb=devices.getDevice(b,com.george_vi.electroenergetics.content.bulb.BulbDevice.class);require(bulb!=null,"Bulb placement missing owner");bulb.temp=999999;
            var tag=new CompoundTag();tag.putDouble("Voltage",285);var source=devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_BATTERY.get(),a,tag);
            sd.registerOrUpdateNodes(a,java.util.List.of(0,1));sd.registerOrUpdateNodes(b,java.util.List.of(0,1));
            for(int n=0;n<2;n++)sd.connect(new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(n,a),new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(n,b),WireFixture());
            boolean lit=false;
            for(int n=0;n<100 && !lit;n++){stepWorld(sd,access);lit=level.getBlockState(b).getValue(com.george_vi.electroenergetics.content.bulb.BulbBlock.LIGHT)>0;}
            require(lit && !bulb.destroyed,"Bulb failed native thermal light or imported legacy Temp");
            source.voltage=1000;
            for(int n=0;n<500 && !bulb.destroyed;n++){stepWorld(sd,access);}
            require(bulb.destroyed && devices.getDevice(b)==bulb && com.george_vi.electroenergetics.CEEBlocks.BROKEN_BULB.has(level.getBlockState(b)),"Bulb overload failed event-boundary housing preservation");
            require(level.getBlockState(b).getValue(com.george_vi.electroenergetics.content.bulb.BulbBlock.LIGHT)==0,"Burned bulb still illuminates");
            System.out.println("NATIVE_WORLD_CEE_BULB_PASSED: accepted thermal light, ignored legacy Temp, overload and broken housing");
        } finally {devices.removeDevice(a);devices.removeDevice(b);level.removeBlock(b,false);}
    }

    private static void verifyCeeHeater(net.minecraft.server.level.ServerLevel level,DevicesSavedData devices) {
        var a=new BlockPos(10,250,8);var b=new BlockPos(12,250,8);var sd=InfrastructureSavedData.load(level);var access=(CeeSimulationAccess)sd.ticker;
        try {
            level.getChunkAt(a);level.getChunkAt(b);level.setBlockAndUpdate(b,com.george_vi.electroenergetics.CEEBlocks.RESISTIVE_HEATER.getDefaultState());
            var heater=devices.getDevice(b);require(heater!=null,"Heater placement missing owner");
            var be=(com.george_vi.electroenergetics.content.resistive_heater.ResistiveHeaterBlockEntity)level.getBlockEntity(b);
            var tag=new CompoundTag();tag.putDouble("Voltage",700);devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_BATTERY.get(),a,tag);
            sd.registerOrUpdateNodes(a,java.util.List.of(0,1));sd.registerOrUpdateNodes(b,java.util.List.of(0,1));
            for(int n=0;n<2;n++)sd.connect(new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(n,a),new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(n,b),WireFixture());
            boolean kindled=false;
            for(int n=0;n<800 && !kindled;n++) {
                stepWorld(sd,access);
                kindled=level.getBlockState(b).getValue(com.george_vi.electroenergetics.content.resistive_heater.ResistiveHeaterBlock.HEAT_LEVEL)==com.simibubi.create.content.processing.burner.BlazeBurnerBlock.HeatLevel.KINDLED;
            }
            require(kindled && be.getUpdateTag(level.registryAccess()).getBoolean("NativeAvailable"),"Native heater never granted accepted processing heat: "+access.electromagnetism$nativeStatus());
            var r=access.electromagnetism$reading(CeeNativeWorld.branchId(heater,"main")).orElseThrow();
            require(Math.abs(be.heat-(r.temperatureKelvin().orElseThrow()-293.15)/600)<1e-6,"Heater presentation independently integrated heat");
            try{be.setVoltage(700);throw new AssertionError("Legacy heater integration remained callable");}catch(UnsupportedOperationException expected){}
            devices.removeDevice(a);sd.ticker.endTick();
            require(be.heat==0 && level.getBlockState(b).getValue(com.george_vi.electroenergetics.content.resistive_heater.ResistiveHeaterBlock.HEAT_LEVEL)==com.simibubi.create.content.processing.burner.BlazeBurnerBlock.HeatLevel.NONE,"Unavailable heater grants processing heat");
            System.out.println("NATIVE_WORLD_CEE_HEATER_PASSED: native temperature, Create processing state, invalidation and blocked legacy integration");
        } finally {devices.removeDevice(a);devices.removeDevice(b);level.removeBlock(b,false);}
    }
    private static com.george_vi.electroenergetics.simulation.infrastructure.WireData WireFixture() {
        return com.george_vi.electroenergetics.simulation.infrastructure.WireData.ofLength(com.george_vi.electroenergetics.CEEWireTypes.STANDARD.get(),2);
    }

    private static void verifyPotentiometer(net.minecraft.server.level.ServerLevel level,DevicesSavedData devices) {
        var a=new BlockPos(10,250,8);var b=new BlockPos(12,250,8);var c=new BlockPos(14,250,8);var sd=InfrastructureSavedData.load(level);var access=(CeeSimulationAccess)sd.ticker;
        try {
            for(var p:java.util.List.of(a,b,c))level.getChunkAt(p);
            var sourceTag=new CompoundTag();sourceTag.putDouble("Voltage",10);devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_BATTERY.get(),a,sourceTag);
            var potTag=new CompoundTag();potTag.putDouble("Resistance",100);potTag.putFloat("Progress",0.25f);
            var pot=devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.POTENTIOMETER.get(),b,potTag);
            var loadTag=new CompoundTag();loadTag.putDouble("Resistance",50);
            var load=devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.RESISTOR.get(),c,loadTag);
            sd.registerOrUpdateNodes(a,java.util.List.of(0,1));sd.registerOrUpdateNodes(b,java.util.List.of(0,1,2));sd.registerOrUpdateNodes(c,java.util.List.of(0,1));
            var positions=java.util.List.of(a,b,c);double rw=0;
            for(var link:new int[][]{{0,0,1,0},{0,1,1,2},{1,1,2,0},{1,2,2,1}}) {
                var wire=com.george_vi.electroenergetics.simulation.infrastructure.WireData.ofLength(com.george_vi.electroenergetics.CEEWireTypes.STANDARD.get(),2);rw=wire.getResistance();
                sd.connect(new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(link[1],positions.get(link[0])),new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(link[3],positions.get(link[2])),wire);
            }
            for(float wiper:new float[]{0,0.25f,0.5f,1}) {
                pot.progress=wiper;
                for(int n=0;n<4;n++){stepWorld(sd,access);}
                double top=0.001+100*wiper,bottom=0.001+100*(1-wiper),loadPath=50+2*rw,parallel=bottom*loadPath/(bottom+loadPath);
                double current=10/(0.001+2*rw+top+parallel)*parallel/loadPath;
                var r=access.electromagnetism$reading(CeeNativeWorld.branchId(load,"main")).orElseThrow();
                require(Math.abs(r.meanCurrent()-current)<1e-8,"Loaded potentiometer differs from parallel-resistance reference");
            }
            pot.progress=Float.NaN;sd.ticker.tick();sd.ticker.endTick();
            require(access.electromagnetism$reading(CeeNativeWorld.branchId(load,"main")).isEmpty(),"Invalid wiper retained valid downstream readings");
            pot.progress=1;access.electromagnetism$resumeMissingInputs();
            System.out.println("NATIVE_WORLD_POTENTIOMETER_PASSED: loaded divider, finite end contacts, four boundary positions and invalid input");
        } finally {devices.removeDevice(a);devices.removeDevice(b);devices.removeDevice(c);}
    }

    private static void verifyBell(net.minecraft.server.level.ServerLevel level,DevicesSavedData devices) {
        var a=new BlockPos(10,250,8);var b=new BlockPos(12,250,8);var sd=InfrastructureSavedData.load(level);var access=(CeeSimulationAccess)sd.ticker;
        try {
            level.getChunkAt(a);level.getChunkAt(b);level.setBlockAndUpdate(b,com.electromagnetism.registry.ModBlocks.ELECTRIC_BELL.getDefaultState());
            var bell=devices.getDevice(b);if(bell==null)bell=devices.addDevice(ModDeviceTypes.ELECTRIC_BELL.get(),b,new CompoundTag());
            var be=(com.electromagnetism.content.acoustics.bell.ElectricBellBlockEntity)level.getBlockEntity(b);
            require(!be.nativeAvailable() && be.getVolume()==0,"Unsolved bell played audio");
            var tag=new CompoundTag();tag.putDouble("Voltage",10);var source=devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_BATTERY.get(),a,tag);
            sd.registerOrUpdateNodes(a,java.util.List.of(0,1));sd.registerOrUpdateNodes(b,java.util.List.of(0,1));double resistance=20.001;
            for(int n=0;n<2;n++) {
                var wire=com.george_vi.electroenergetics.simulation.infrastructure.WireData.ofLength(com.george_vi.electroenergetics.CEEWireTypes.STANDARD.get(),2);resistance+=wire.getResistance();
                sd.connect(new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(n,a),new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(n,b),wire);
            }
            for(int n=0;n<12;n++){stepWorld(sd,access);}
            require(be.nativeAvailable() && Math.abs(be.getVolume()-10/resistance)<0.001,"Bell audio did not consume accepted coil power");
            source.voltage=0;
            for(int n=0;n<15;n++){stepWorld(sd,access);}
            require(be.nativeAvailable() && be.getVolume()<1e-5,"Bell magnetic decay did not stop audio");
            devices.removeDevice(a);sd.ticker.endTick();require(!be.nativeAvailable() && be.getVolume()==0,"Unavailable bell continued sounding");
            System.out.println("NATIVE_WORLD_BELL_PASSED: finite coil, magnetic decay and accepted-power audio availability");
        } finally {devices.removeDevice(a);devices.removeDevice(b);level.removeBlock(b,false);}
    }

    private static void verifySableTransfer(net.minecraft.server.level.ServerLevel level,DevicesSavedData devices) throws Exception {
        var sd=InfrastructureSavedData.load(level);var access=(CeeSimulationAccess)sd.ticker;
        var a=new BlockPos(10,250,8);var b=new BlockPos(12,250,8);var destination=new BlockPos(12,250,12);
        try {
            for(var pos:java.util.List.of(a,b,destination))level.getChunkAt(pos);
            level.setBlockAndUpdate(a,com.george_vi.electroenergetics.CEEBlocks.CREATIVE_BATTERY.getDefaultState());
            level.setBlockAndUpdate(b,com.george_vi.electroenergetics.CEEBlocks.CAPACITOR.getDefaultState());
            devices.tick();
            var source=devices.getDevice(a,com.george_vi.electroenergetics.content.creative_battery.CreativeBatteryDevice.class);
            var capacitor=devices.getDevice(b,com.george_vi.electroenergetics.content.electronic_components.capacitor.CapacitorDevice.class);
            require(source!=null && capacitor!=null,"Sable fixture did not initialize devices");source.voltage=1;source.acFrequency=0;
            sd.registerOrUpdateNodes(a,java.util.List.of(0,1));sd.registerOrUpdateNodes(b,java.util.List.of(0,1));
            var wireIds=new java.util.HashSet<UUID>();
            for(int pin=0;pin<2;pin++) {
                var wire=com.george_vi.electroenergetics.simulation.infrastructure.WireData.ofLength(com.george_vi.electroenergetics.CEEWireTypes.STANDARD.get(),2);
                wireIds.add(((CeeWireIdentity)wire).electromagnetism$wireIdentity());
                sd.connect(new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(pin,a),new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(pin,b),wire);
            }
            for(int n=0;n<12;n++){stepWorld(sd,access);}
            UUID branch=CeeNativeWorld.branchId(capacitor,"main");
            var charged=access.electromagnetism$reading(branch).orElseThrow();require(charged.meanVoltage()>0.99,"Sable fixture capacitor not charged");
            var identity=((CeeDeviceIdentity)capacitor).electromagnetism$nativeIdentity();
            var before=access.electromagnetism$saveNative();
            var transformType=Class.forName("dev.ryanhcode.sable.api.SubLevelAssemblyHelper$AssemblyTransform");
            var transform=transformType.getConstructor(BlockPos.class,BlockPos.class,int.class,net.minecraft.world.level.block.Rotation.class,net.minecraft.server.level.ServerLevel.class).newInstance(b,destination,0,net.minecraft.world.level.block.Rotation.NONE,level);
            Class.forName("dev.ryanhcode.sable.api.SubLevelAssemblyHelper").getMethod("moveBlocks",net.minecraft.server.level.ServerLevel.class,transformType,Iterable.class).invoke(null,level,transform,java.util.List.of(b));
            require(!devices.devicesToAddSable.isEmpty(),"Actual Sable move did not use CEE transfer queue");
            require(!new CeeNativeWorld(sd).capture().blockers().isEmpty(),"Intermediate Sable geometry was accepted as a topology");
            sd.ticker.tick();
            require(java.util.Arrays.equals(before.getByteArray("State"),access.electromagnetism$saveNative().getByteArray("State")),"Pending Sable handoff advanced or discarded native state");
            devices.tick();
            var moved=devices.getDevice(destination);
            require(moved!=null && moved!=capacitor && devices.getDevice(b)==null,"Sable retained two physical owners");
            require(!capacitor.isValid() && identity.equals(((CeeDeviceIdentity)moved).electromagnetism$nativeIdentity()),"Sable changed incarnation or left source valid");
            require(java.util.Arrays.equals(before.getByteArray("State"),access.electromagnetism$saveNative().getByteArray("State")),"Sable handoff rewrote accepted storage before a boundary");
            var movedIds=new java.util.HashSet<UUID>();
            for(int pin=0;pin<2;pin++)for(var link:sd.getConnections(new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(pin,destination)))
                movedIds.add(((CeeWireIdentity)sd.getConnectionData(link)).electromagnetism$wireIdentity());
            require(movedIds.equals(wireIds),"Sable wire incarnation changed");
            access.electromagnetism$resumeMissingInputs();
            for(int n=0;n<4;n++){stepWorld(sd,access);}
            var resumed=access.electromagnetism$reading(branch).orElseThrow();
            require(resumed.meanVoltage()>0.99 && Math.abs(resumed.meanCurrent())<1e-6,"Moved capacitor was recharged from zero or lost energy");
            devices.devicesToAddSable.add(net.createmod.catnip.data.Pair.of(b,moved));devices.devicesToAddSable.add(net.createmod.catnip.data.Pair.of(b,moved));
            devices.tick();require(devices.getDevice(destination)==moved && devices.getDevice(b)==null,"Rejected transfer modified owners");
            require(!((NativeSableTransfers)devices).electromagnetism$transferProblem().isBlank(),"Rejected transfer lacked diagnostic");
            devices.devicesToAddSable.clear();
            System.out.println("NATIVE_SABLE_TRANSFER_PASSED: actual moveBlocks, unique incarnation, charged state, wire identity and rejected batch rollback");
        } finally {devices.devicesToAddSable.clear();for(var pos:java.util.List.of(a,b,destination)){devices.removeDevice(pos);level.removeBlock(pos,false);}}
    }

    private static void verifyCommandedSwitches(net.minecraft.server.level.ServerLevel level,DevicesSavedData devices) {
        var sd=InfrastructureSavedData.load(level);var access=(CeeSimulationAccess)sd.ticker;
        long originalGameTime=level.getGameTime();
        var a=new BlockPos(10,250,8);var b=new BlockPos(12,250,8);var c=new BlockPos(14,250,8);
        for(int kind=0;kind<4;kind++) {
            try {
                for(var pos:java.util.List.of(a,b,c))level.getChunkAt(pos);
                level.setBlockAndUpdate(b,kind==0?com.george_vi.electroenergetics.CEEBlocks.CUT_OFF_SWITCH.getDefaultState():kind==1?com.george_vi.electroenergetics.CEEBlocks.DOUBLE_SWITCH.getDefaultState():kind==2?com.george_vi.electroenergetics.CEEBlocks.REDSTONE_RELAY.getDefaultState():com.george_vi.electroenergetics.CEEBlocks.MOMENTARY_SWITCH.getDefaultState());
                var device=devices.getDevice(b);require(device!=null,"Switch placement did not create physical owner");
                var sourceTag=new CompoundTag();sourceTag.putDouble("Voltage",10);
                devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_BATTERY.get(),a,sourceTag);
                var loadTag=new CompoundTag();loadTag.putDouble("Resistance",5);
                var load=devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_RESISTOR.get(),c,loadTag);
                sd.registerOrUpdateNodes(a,java.util.List.of(0,1));sd.registerOrUpdateNodes(b,kind==1?java.util.List.of(0,1,2,3):java.util.List.of(0,1));sd.registerOrUpdateNodes(c,java.util.List.of(0,1));
                var connections=kind==1?new int[][]{{0,0,1,0},{1,2,2,0},{2,1,1,3},{1,1,0,1}}:new int[][]{{0,0,1,0},{1,1,2,0},{2,1,0,1}};
                var positions=java.util.List.of(a,b,c);double wireResistance=0;
                for(var link:connections) {
                    var wire=com.george_vi.electroenergetics.simulation.infrastructure.WireData.ofLength(com.george_vi.electroenergetics.CEEWireTypes.STANDARD.get(),2);wireResistance+=wire.getResistance();
                    sd.connect(new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(link[1],positions.get(link[0])),new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(link[3],positions.get(link[2])),wire);
                }
                for(int mode=0;mode<4;mode++) {
                    boolean closed=mode%2==0;
                    if(device instanceof com.george_vi.electroenergetics.content.cut_off_switch.CutOffSwitchDevice contact)contact.isClosed=closed;
                    else if(device instanceof com.george_vi.electroenergetics.content.cut_off_switch.MomentarySwitchDevice button) {
                        button.closedTicks=closed?5:-1;level.getServer().getWorldData().overworldData().setGameTime(level.getGameTime()+1);
                    }
                    else level.setBlockAndUpdate(b,level.getBlockState(b).setValue(com.george_vi.electroenergetics.content.redstone_relay.RedstoneRelayBlock.POWERED,mode>=2).setValue(com.george_vi.electroenergetics.content.redstone_relay.RedstoneRelayBlock.INVERTED,closed^(mode>=2)));
                    for(int n=0;n<4;n++){stepWorld(sd,access);}
                    var r=access.electromagnetism$reading(CeeNativeWorld.branchId(load,"main")).orElseThrow();
                    double expected=10/(5+0.001+wireResistance+(kind==1?2:1)*(closed?0.001:1e12));
                    require(Math.abs(r.meanCurrent()-expected)<Math.max(1e-9,expected*1e-7),"Switch terminal or command mismatch: "+kind+"/"+mode+" "+r.meanCurrent()+" vs "+expected);
                }
                if(device instanceof com.george_vi.electroenergetics.content.cut_off_switch.MomentarySwitchDevice button) {
                    button.closedTicks=5;
                    for(int hold=0;hold<6;hold++) {
                        level.getServer().getWorldData().overworldData().setGameTime(level.getGameTime()+1);
                        for(int repeat=0;repeat<3;repeat++){stepWorld(sd,access);}
                        require(button.closedTicks==4-hold,"Repeated native requests consumed button game ticks");
                        require(level.getBlockState(b).getValue(com.george_vi.electroenergetics.content.cut_off_switch.MomentarySwitchBlock.CLOSED)==(hold<5),"Button did not release after five game ticks");
                        var r=access.electromagnetism$reading(CeeNativeWorld.branchId(load,"main")).orElseThrow();
                        double expected=10/(5+0.001+wireResistance+(hold<5?0.001:1e12));
                        require(Math.abs(r.meanCurrent()-expected)<Math.max(1e-9,expected*1e-7),"Momentary release did not reach native contact");
                        var saved=new CompoundTag();button.write(saved);button.read(saved);
                        require(button.closedTicks==4-hold,"Saving button lost remaining input duration");
                    }
                }
            } finally {devices.removeDevice(a);devices.removeDevice(b);devices.removeDevice(c);level.removeBlock(b,false);level.getServer().getWorldData().overworldData().setGameTime(originalGameTime);}
        }
        System.out.println("NATIVE_WORLD_SWITCHES_PASSED: single/double pole isolation, repeated boundary commands redstone inversion, five-tick momentary release and duplicate capture");
    }

    private static void verifyTransformer(net.minecraft.server.level.ServerLevel level,DevicesSavedData devices) {
        var sd=InfrastructureSavedData.load(level);var access=(CeeSimulationAccess)sd.ticker;
        var a=new BlockPos(10,250,8);var b=new BlockPos(12,250,8);var c=new BlockPos(14,250,8);
        try {
            for(var pos:java.util.List.of(a,b,c))level.getChunkAt(pos);
            level.setBlockAndUpdate(b,com.george_vi.electroenergetics.CEEBlocks.TRANSFORMER.getDefaultState());
            var transformer=devices.getDevice(b,com.george_vi.electroenergetics.content.transmission_distribution.transformer.TransformerDevice.class);
            if(transformer==null)transformer=devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.TRANSFORMER.get(),b,new CompoundTag());
            transformer.ratio=2;transformer.temp=999999;
            var sourceTag=new CompoundTag();sourceTag.putDouble("Voltage",10);sourceTag.putDouble("ACFrequency",50);
            devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_BATTERY.get(),a,sourceTag);
            var loadTag=new CompoundTag();loadTag.putDouble("Resistance",10);
            var load=devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_RESISTOR.get(),c,loadTag);
            sd.registerOrUpdateNodes(a,java.util.List.of(0,1));sd.registerOrUpdateNodes(b,java.util.List.of(0,1,2,3));sd.registerOrUpdateNodes(c,java.util.List.of(0,1));
            double wireResistance=0;
            for(int pin=0;pin<4;pin++) {
                var wire=com.george_vi.electroenergetics.simulation.infrastructure.WireData.ofLength(com.george_vi.electroenergetics.CEEWireTypes.STANDARD.get(),2);wireResistance=wire.getResistance();
                sd.connect(new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(pin,b),new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(pin%2,pin<2?a:c),wire);
            }
            double start=access.electromagnetism$saveNative().getDouble("AcceptedTime"),last=start,sum=0,duration=0;
            for(int n=0;n<5000 && last<start+1.2;n++) {
                var snapshot=advanceWorld(sd,access);
                double now=snapshot.getDouble("AcceptedTime");
                if(now>last && last>start+0.2) {
                    var r=access.electromagnetism$reading(CeeNativeWorld.branchId(load,"main")).orElseThrow();
                    double dt=r.windowEnd()-r.windowStart();sum+=r.rmsVoltage()*r.rmsVoltage()*dt;duration+=dt;
                }
                last=now;
            }
            require(last>=start+1.2 && duration>0.9,"Transformer failed to reach reference window (accepted="+(last-start)+", sampled="+duration+"): "+access.electromagnetism$nativeStatus());
            var config=com.electromagnetism.config.ModConfigs.server();double lm=config.nativeTransformerMagnetizingHenries.getF(),leak=config.nativeTransformerLeakageHenries.getF();
            double omega=2*Math.PI*50,rp=config.nativeTransformerWindingOhms.getF()+0.002+2*wireResistance,rs=config.nativeTransformerWindingOhms.getF()+0.001+2*wireResistance+10;
            double xp=omega*(lm+leak),xs=omega*(lm/4+leak),xm=omega*lm/2;
            double determinant=Math.hypot(rp*rs-xp*xs+xm*xm,rp*xs+rs*xp);
            double expected=10*xm/determinant*10/Math.sqrt(2),measured=Math.sqrt(sum/duration);
            require(Math.abs(measured/expected-1)<0.03,"Transformer differs from independent complex impedance solution: "+measured+" vs "+expected);
            var be=(com.george_vi.electroenergetics.content.transmission_distribution.transformer.TransformerBlockEntity)level.getBlockEntity(b);
            var packet=be.getUpdateTag(level.registryAccess());
            require(packet.getBoolean("NativeAvailable") && packet.getDouble("Power")>0 && packet.getDouble("SV")>0,"Transformer display did not receive native RMS/power");
            devices.removeDevice(c);sd.ticker.endTick();
            require(!be.getUpdateTag(level.registryAccess()).getBoolean("NativeAvailable"),"Obsolete transformer display remained valid");
            System.out.println("NATIVE_WORLD_TRANSFORMER_PASSED: isolated reciprocal windings, finite magnetization/leakage, copper losses and accepted RMS/power");
        } finally {devices.removeDevice(a);devices.removeDevice(b);devices.removeDevice(c);level.removeBlock(b,false);}
    }

    private static void verifyTransformerCore(net.minecraft.server.level.ServerLevel level,DevicesSavedData devices) {
        var sd=InfrastructureSavedData.load(level);var access=(CeeSimulationAccess)sd.ticker;
        var a=new BlockPos(10,250,8);var b=new BlockPos(12,250,8);var c=new BlockPos(14,250,8);
        try {
            for(var pos:java.util.List.of(a,b,c))level.getChunkAt(pos);
            var coreBlock=com.george_vi.electroenergetics.CEEBlocks.TRANSFORMER_CORE.get();
            var state=coreBlock.defaultBlockState().setValue(com.george_vi.electroenergetics.content.transmission_distribution.transformer.TransformerCoreBlock.FACING,net.minecraft.core.Direction.EAST);
            level.setBlockAndUpdate(b,state);coreBlock.setPlacedBy(level,b,state,null,net.minecraft.world.item.ItemStack.EMPTY);
            var transformer=devices.getDevice(b,com.george_vi.electroenergetics.content.transmission_distribution.transformer.TransformerCoreDevice.class);
            require(transformer!=null && devices.getDevice(b.east())!=null,"Transformer core pair did not create both owners");
            transformer.ratio=2;transformer.temp=999999;transformer.heatDissipation=999999;
            var sourceTag=new CompoundTag();sourceTag.putDouble("Voltage",10);sourceTag.putDouble("ACFrequency",50);
            devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_BATTERY.get(),a,sourceTag);
            var loadTag=new CompoundTag();loadTag.putDouble("Resistance",10);
            var load=devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_RESISTOR.get(),c,loadTag);
            sd.registerOrUpdateNodes(a,java.util.List.of(0,1));sd.registerOrUpdateNodes(b,java.util.List.of(0,1));sd.registerOrUpdateNodes(b.east(),java.util.List.of(0,1));sd.registerOrUpdateNodes(c,java.util.List.of(0,1));
            double wireResistance=0;
            for(int pin=0;pin<4;pin++) {
                var wire=com.george_vi.electroenergetics.simulation.infrastructure.WireData.ofLength(com.george_vi.electroenergetics.CEEWireTypes.STANDARD.get(),2);wireResistance=wire.getResistance();
                sd.connect(new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(pin%2,pin<2?b:b.east()),new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(pin%2,pin<2?a:c),wire);
            }
            double start=access.electromagnetism$saveNative().getDouble("AcceptedTime"),last=start,sum=0,duration=0;
            for(int n=0;n<5000 && last<start+1.2;n++) {
                var snapshot=advanceWorld(sd,access);
                double now=snapshot.getDouble("AcceptedTime");
                if(now>last && last>start+0.2) {
                    var r=access.electromagnetism$reading(CeeNativeWorld.branchId(load,"main")).orElseThrow();
                    double dt=r.windowEnd()-r.windowStart();sum+=r.rmsVoltage()*r.rmsVoltage()*dt;duration+=dt;
                }
                last=now;
            }
            require(last>=start+1.2 && duration>0.9,"Transformer failed to reach reference window (accepted="+(last-start)+", sampled="+duration+"): "+access.electromagnetism$nativeStatus());
            var config=com.electromagnetism.config.ModConfigs.server();double lm=config.nativeTransformerMagnetizingHenries.getF(),leak=config.nativeTransformerLeakageHenries.getF();
            double omega=2*Math.PI*50,rp=config.nativeTransformerWindingOhms.getF()+0.002+2*wireResistance,rs=config.nativeTransformerWindingOhms.getF()+0.001+2*wireResistance+10;
            double xp=omega*(lm+leak),xs=omega*(lm/4+leak),xm=omega*lm/2;
            double determinant=Math.hypot(rp*rs-xp*xs+xm*xm,rp*xs+rs*xp);
            double expected=10*xm/determinant*10/Math.sqrt(2),measured=Math.sqrt(sum/duration);
            require(Math.abs(measured/expected-1)<0.03,"Transformer differs from independent complex impedance solution: "+measured+" vs "+expected);
            var be=(com.george_vi.electroenergetics.content.transmission_distribution.transformer.TransformerCoreBlockEntity)level.getBlockEntity(b);
            var packet=be.getUpdateTag(level.registryAccess());
            require(packet.getBoolean("NativeAvailable") && packet.getDouble("Power")>0 && packet.getDouble("SV")>0,"Transformer display did not receive native RMS/power");
            var other=(com.george_vi.electroenergetics.content.transmission_distribution.transformer.TransformerCoreBlockEntity)level.getBlockEntity(b.east());
            require(other.getUpdateTag(level.registryAccess()).getBoolean("NativeAvailable"),"Secondary core display has no accepted result");
            double baseCooling=NativeTransformerGeometry.coolingPerWinding(level,b);
            level.setBlockAndUpdate(b.below(),net.minecraft.world.level.block.Blocks.IRON_BLOCK.defaultBlockState());
            require(Math.abs(NativeTransformerGeometry.coolingPerWinding(level,b)-baseCooling-0.5*config.nativeTransformerFinCoolingWattsPerKelvin.getF())<1e-9,"Core fin geometry not converted to SI conductance");
            for(int n=0;n<4;n++){stepWorld(sd,access);}
            double limit=be.getUpdateTag(level.registryAccess()).getDouble("HeatDissipationFactor");be.lazyTick();
            require(Math.abs(limit-200*NativeTransformerGeometry.coolingPerWinding(level,b))<1e-7 && be.getUpdateTag(level.registryAccess()).getDouble("HeatDissipationFactor")==limit,"Legacy lazyTick overwrote native cooling display");
            require(new CeeNativeWorld(sd).capture().branches().stream().filter(branch->branch.model().equals("coupled_windings")).count()==1,"Core halves duplicated magnetic state");
            devices.removeDevice(c);sd.ticker.endTick();
            require(!be.getUpdateTag(level.registryAccess()).getBoolean("NativeAvailable"),"Obsolete transformer display remained valid");
            System.out.println("NATIVE_WORLD_TRANSFORMER_CORE_PASSED: paired physical owner, SI cooling fins, blocked legacy cooling, isolated reciprocal windings, finite magnetization/leakage, copper losses and accepted RMS/power");
        } finally {devices.removeDevice(a);devices.removeDevice(b);devices.removeDevice(c);devices.removeDevice(b.east());level.removeBlock(b,false);level.removeBlock(b.east(),false);level.removeBlock(b.below(),false);}
    }

    private static void verifyCurrentTransformer(net.minecraft.server.level.ServerLevel level,DevicesSavedData devices) {
        var sd=InfrastructureSavedData.load(level);var access=(CeeSimulationAccess)sd.ticker;
        var a=new BlockPos(10,250,8);var b=new BlockPos(12,250,8);var c=new BlockPos(14,250,8);
        for(int height:new int[]{1,3})try {
            for(var pos:java.util.List.of(a,b,c))level.getChunkAt(pos);
            for(int part=0;part<height;part++)level.setBlockAndUpdate(b.above(part),com.george_vi.electroenergetics.CEEBlocks.CURRENT_TRANSFORMER.getDefaultState()
                    .setValue(com.george_vi.electroenergetics.content.transmission_distribution.current_transformer.CurrentTransformerBlock.BOTTOM,part==0)
                    .setValue(com.george_vi.electroenergetics.content.transmission_distribution.current_transformer.CurrentTransformerBlock.TOP,part==height-1));
            var transformer=devices.getDevice(b.above(height-1),com.george_vi.electroenergetics.content.transmission_distribution.current_transformer.CurrentTransformerDevice.class);
            require(transformer!=null,"Current transformer placement missing top owner");transformer.ratio=2;transformer.temp=999999;
            var sourceTag=new CompoundTag();sourceTag.putDouble("Voltage",10);sourceTag.putDouble("ACFrequency",50);
            devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_BATTERY.get(),a,sourceTag);
            var loadTag=new CompoundTag();loadTag.putDouble("Resistance",10);
            var load=devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_RESISTOR.get(),c,loadTag);
            sd.registerOrUpdateNodes(a,java.util.List.of(0,1));sd.registerOrUpdateNodes(b,java.util.List.of(2,3));sd.registerOrUpdateNodes(b.above(height-1),height==1?java.util.List.of(0,1,2,3):java.util.List.of(0,1));sd.registerOrUpdateNodes(c,java.util.List.of(0,1));
            double wireResistance=0;
            for(int pin=0;pin<4;pin++) {
                var wire=com.george_vi.electroenergetics.simulation.infrastructure.WireData.ofLength(com.george_vi.electroenergetics.CEEWireTypes.STANDARD.get(),2);wireResistance=wire.getResistance();
                sd.connect(new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(pin,pin<2?b.above(height-1):b),new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(pin%2,pin<2?a:c),wire);
            }
            double start=access.electromagnetism$saveNative().getDouble("AcceptedTime"),last=start,sum=0,duration=0;
            for(int n=0;n<5000 && last<start+1.2;n++) {
                var snapshot=advanceWorld(sd,access);
                double now=snapshot.getDouble("AcceptedTime");
                if(now>last && last>start+0.2) {
                    var r=access.electromagnetism$reading(CeeNativeWorld.branchId(load,"main")).orElseThrow();
                    double dt=r.windowEnd()-r.windowStart();sum+=r.rmsVoltage()*r.rmsVoltage()*dt;duration+=dt;
                }
                last=now;
            }
            require(last>=start+1.2 && duration>0.9,"Transformer failed to reach reference window (accepted="+(last-start)+", sampled="+duration+"): "+access.electromagnetism$nativeStatus());
            var config=com.electromagnetism.config.ModConfigs.server();double lm=config.nativeTransformerMagnetizingHenries.getF(),leak=config.nativeTransformerLeakageHenries.getF();
            double omega=2*Math.PI*50,rp=config.nativeTransformerWindingOhms.getF()+0.001+2*wireResistance,rs=config.nativeTransformerWindingOhms.getF()+2*wireResistance+10;
            double xp=omega*(lm+leak),xs=omega*(lm/4+leak),xm=omega*lm/2;
            double determinant=Math.hypot(rp*rs-xp*xs+xm*xm,rp*xs+rs*xp);
            double expected=10*xm/determinant*10/Math.sqrt(2),measured=Math.sqrt(sum/duration);
            require(Math.abs(measured/expected-1)<0.03,"Transformer differs from independent complex impedance solution: "+measured+" vs "+expected);
            var frame=new CeeNativeWorld(sd).capture();
            require(frame.branches().stream().filter(branch->branch.model().equals("coupled_windings")).count()==1,"Current transformer stack duplicated magnetic state");
            require(devices.getDevice(b.above(height-1))==transformer,"Legacy current transformer Temp caused damage");
        } finally {
            devices.removeDevice(a);devices.removeDevice(c);
            for(int part=0;part<height;part++){devices.removeDevice(b.above(part));level.removeBlock(b.above(part),false);}
        }
        System.out.println("NATIVE_WORLD_CURRENT_TRANSFORMER_PASSED: single/three-block stack, one shared magnetic state, top-primary/bottom-secondary and independent impedance solution");
    }

    private static void verifyVariac(net.minecraft.server.level.ServerLevel level,DevicesSavedData devices) {
        var sd=InfrastructureSavedData.load(level);var access=(CeeSimulationAccess)sd.ticker;
        var a=new BlockPos(10,250,8);var b=new BlockPos(12,250,8);var c=new BlockPos(14,250,8);
        for(float tap:new float[]{0,0.25f,1})try {
            for(var pos:java.util.List.of(a,b,c))level.getChunkAt(pos);
            level.setBlockAndUpdate(b,com.george_vi.electroenergetics.CEEBlocks.VARIAC.getDefaultState());
            var variac=devices.getDevice(b,com.george_vi.electroenergetics.content.variac.VariacDevice.class);
            require(variac!=null,"Variac placement missing owner");variac.ratio=2;variac.progress=tap;variac.temp=999999;
            var sourceTag=new CompoundTag();sourceTag.putDouble("Voltage",10);sourceTag.putDouble("ACFrequency",50);
            devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_BATTERY.get(),a,sourceTag);
            var loadTag=new CompoundTag();loadTag.putDouble("Resistance",10);
            var load=devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_RESISTOR.get(),c,loadTag);
            sd.registerOrUpdateNodes(a,java.util.List.of(0,1));sd.registerOrUpdateNodes(b,java.util.List.of(0,1,2));sd.registerOrUpdateNodes(c,java.util.List.of(0,1));
            double wr=0;var positions=java.util.List.of(a,b,c);
            for(var link:new int[][]{{0,0,1,0},{0,1,1,1},{1,2,2,0},{1,1,2,1}}) {
                var wire=com.george_vi.electroenergetics.simulation.infrastructure.WireData.ofLength(com.george_vi.electroenergetics.CEEWireTypes.STANDARD.get(),2);wr=wire.getResistance();
                sd.connect(new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(link[1],positions.get(link[0])),new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(link[3],positions.get(link[2])),wire);
            }
            double start=access.electromagnetism$saveNative().getDouble("AcceptedTime"),last=start,sum=0,duration=0;
            for(int n=0;n<5000 && last<start+1.2;n++) {
                var snapshot=advanceWorld(sd,access);
                double now=snapshot.getDouble("AcceptedTime");
                if(now>last && last>start+0.2) {
                    var r=access.electromagnetism$reading(CeeNativeWorld.branchId(load,"main")).orElseThrow();
                    double dt=r.windowEnd()-r.windowStart();sum+=r.rmsVoltage()*r.rmsVoltage()*dt;duration+=dt;
                }last=now;
            }
            require(last>=start+1.2 && duration>0.9,"Variac failed to reach reference window (accepted="+(last-start)+", sampled="+duration+"): "+access.electromagnetism$nativeStatus());
            var config=com.electromagnetism.config.ModConfigs.server();double lm=config.nativeTransformerMagnetizingHenries.getF(),leak=config.nativeTransformerLeakageHenries.getF(),gain=2*tap;
            double omega=2*Math.PI*50,rp=config.nativeTransformerWindingOhms.getF()+0.001+2*wr,rs=config.nativeTransformerWindingOhms.getF()+2*wr+10;
            double xp=omega*(lm+leak),xs=omega*(lm*gain*gain+leak),xm=omega*lm*gain;
            double expected=100*xm/Math.hypot(rp*rs-xp*xs+xm*xm,rp*xs+rs*xp)/Math.sqrt(2),measured=Math.sqrt(sum/duration);
            require(Math.abs(measured-expected)<Math.max(1e-8,expected*0.03),"Variac differs from independent complex impedance solution: "+tap+" "+measured+" vs "+expected);
            require(devices.getDevice(b)==variac,"Legacy variac Temp caused damage");
            variac.progress=Float.NaN;sd.ticker.tick();sd.ticker.endTick();
            require(access.electromagnetism$nativeStatus().status()==Status.UNSUPPORTED && access.electromagnetism$reading(CeeNativeWorld.branchId(load,"main")).isEmpty(),"Invalid variac tap retained valid readings");
            variac.progress=tap;
            access.electromagnetism$resumeMissingInputs();
        } finally {devices.removeDevice(a);devices.removeDevice(b);devices.removeDevice(c);level.removeBlock(b,false);}
        System.out.println("NATIVE_WORLD_VARIAC_PASSED: common-return windings, zero/half/double voltage gain, copper loss and invalid tap rejection");
    }

    private static void verifyConcretePole(net.minecraft.server.level.ServerLevel level,DevicesSavedData devices) {
        var sd=InfrastructureSavedData.load(level);var access=(CeeSimulationAccess)sd.ticker;
        var a=new BlockPos(10,250,8);var b=new BlockPos(12,250,8);var c=new BlockPos(14,250,8);
        for(int height:new int[]{3,4})try {
            for(var pos:java.util.List.of(a,b,c))level.getChunkAt(pos);
            for(int part=0;part<height;part++)level.setBlockAndUpdate(b.above(part),com.george_vi.electroenergetics.CEEBlocks.CONCRETE_POLE.getDefaultState());
            var sourceTag=new CompoundTag();sourceTag.putDouble("Voltage",10);var loadTag=new CompoundTag();loadTag.putDouble("Resistance",5);
            devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_BATTERY.get(),a,sourceTag);
            var load=devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_RESISTOR.get(),c,loadTag);
            sd.registerOrUpdateNodes(a,java.util.List.of(0,1));sd.registerOrUpdateNodes(c,java.util.List.of(0,1));
            sd.registerOrUpdateNodes(b,java.util.List.of(0,1,2,3));sd.registerOrUpdateNodes(b.above(height-1),java.util.List.of(0,1,2,3));
            double resistance=(height+1)*com.electromagnetism.config.ModConfigs.server().nativePoleConductorOhmsPerMetre.getF();
            var positions=java.util.List.of(a,b,b.above(height-1),c);
            for(var link:new int[][]{{0,0,1,2},{2,2,3,0},{3,1,0,1}}) {
                var wire=com.george_vi.electroenergetics.simulation.infrastructure.WireData.ofLength(com.george_vi.electroenergetics.CEEWireTypes.STANDARD.get(),2);resistance+=wire.getResistance();
                sd.connect(new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(link[1],positions.get(link[0])),new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(link[3],positions.get(link[2])),wire);
            }
            for(int n=0;n<4;n++){stepWorld(sd,access);}
            var reading=access.electromagnetism$reading(CeeNativeWorld.branchId(load,"main")).orElseThrow();
            require(Math.abs(reading.meanCurrent()-10/(5+0.001+resistance))<1e-7,"Pole end ring or vertical segment resistance incorrect for height "+height);
            level.removeBlock(b.above(),false);devices.removeDevice(b.above());
            for(int n=0;n<4;n++){stepWorld(sd,access);}
            require(Math.abs(access.electromagnetism$reading(CeeNativeWorld.branchId(load,"main")).orElseThrow().meanCurrent())<1e-10,"Pole retained hidden connection through removed middle block");
        } finally {
            devices.removeDevice(a);devices.removeDevice(c);
            for(int part=0;part<height;part++){devices.removeDevice(b.above(part));level.removeBlock(b.above(part),false);}
        }
        System.out.println("NATIVE_WORLD_POLE_PASSED: odd/even stack height, end-ring current division, explicit SI segment loss and middle removal");
    }

    private static void useFuseHolder(net.minecraft.server.level.ServerLevel level,BlockPos pos,boolean first,net.minecraft.world.item.ItemStack stack) throws Exception {
        var player=net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level);
        var method=com.george_vi.electroenergetics.content.fuse.FuseHolderBlock.class.getDeclaredMethod("useItemOn",net.minecraft.world.item.ItemStack.class,net.minecraft.world.level.block.state.BlockState.class,Level.class,BlockPos.class,net.minecraft.world.entity.player.Player.class,net.minecraft.world.InteractionHand.class,net.minecraft.world.phys.BlockHitResult.class);
        method.setAccessible(true);
        var hit=new net.minecraft.world.phys.BlockHitResult(new net.minecraft.world.phys.Vec3(pos.getX()+(first?0.25:0.75),pos.getY()+0.5,pos.getZ()),net.minecraft.core.Direction.NORTH,pos,false);
        var result=method.invoke(level.getBlockState(pos).getBlock(),stack,level.getBlockState(pos),level,pos,player,net.minecraft.world.InteractionHand.MAIN_HAND,hit);
        require(result==net.minecraft.world.ItemInteractionResult.SUCCESS,"Fuse holder item interaction failed");
    }
    private static void verifyFuseHolder(net.minecraft.server.level.ServerLevel level,DevicesSavedData devices) throws Exception {
        var sd=InfrastructureSavedData.load(level);var access=(CeeSimulationAccess)sd.ticker;
        var a=new BlockPos(10,250,8);var b=new BlockPos(12,250,8);var c=new BlockPos(14,250,8);var d=new BlockPos(12,250,10);
        try {
            for(var pos:java.util.List.of(a,b,c,d))level.getChunkAt(pos);
            level.setBlockAndUpdate(b,com.george_vi.electroenergetics.CEEBlocks.FUSE_HOLDER.getDefaultState()
                    .setValue(com.george_vi.electroenergetics.foundation.base.DirectionalRolledDeviceBlock.FACING,net.minecraft.core.Direction.NORTH)
                    .setValue(com.george_vi.electroenergetics.foundation.base.DirectionalRolledDeviceBlock.ROLL,false));
            var device=devices.getDevice(b);var holder=(NativeFuseHolder)device;
            var be=(com.george_vi.electroenergetics.content.fuse.FuseHolderBlockEntity)level.getBlockEntity(b);
            var fuse=com.george_vi.electroenergetics.CEEBlocks.FUSE.asStack();fuse.set(com.george_vi.electroenergetics.CEEDataComponents.FUSE_AMPERAGE,10);
            useFuseHolder(level,b,true,fuse);useFuseHolder(level,b,false,com.george_vi.electroenergetics.CEEBlocks.INDICATOR_BULB.asStack());
            var firstId=holder.electromagnetism$slots().getFirst().identity();var secondId=holder.electromagnetism$slots().getLast().identity();
            require(!firstId.equals(secondId),"Fuse holder slots share physical identity");
            var sourceTag=new CompoundTag();sourceTag.putDouble("Voltage",10);var lightTag=new CompoundTag();lightTag.putDouble("Voltage",35);var loadTag=new CompoundTag();loadTag.putDouble("Resistance",0.5);
            devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_BATTERY.get(),a,sourceTag);
            devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_BATTERY.get(),d,lightTag);
            var load=devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_RESISTOR.get(),c,loadTag);
            for(var pos:java.util.List.of(a,c,d))sd.registerOrUpdateNodes(pos,java.util.List.of(0,1));sd.registerOrUpdateNodes(b,java.util.List.of(0,1,2,3));
            var positions=java.util.List.of(a,b,c,d);double wireResistance=0;
            for(var link:new int[][]{{0,0,1,1},{1,3,2,0},{2,1,0,1},{3,0,1,0},{3,1,1,2}}) {
                var wire=com.george_vi.electroenergetics.simulation.infrastructure.WireData.ofLength(com.george_vi.electroenergetics.CEEWireTypes.STANDARD.get(),2);
                if(link[0]!=3)wireResistance+=wire.getResistance();
                sd.connect(new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(link[1],positions.get(link[0])),new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(link[3],positions.get(link[2])),wire);
            }
            double start=access.electromagnetism$saveNative().getDouble("AcceptedTime"),now=start;
            for(int n=0;n<500 && !holder.electromagnetism$slots().getFirst().broken();n++) {
                now=stepWorld(sd,access).getDouble("AcceptedTime");
            }
            double current=10/(0.502+wireResistance),expected=-Math.log(1-Math.pow(10/current,2));
            require(holder.electromagnetism$slots().getFirst().broken() && Math.abs(now-start-expected)<0.002,"Held fuse missed analytic event boundary: "+(now-start)+" vs "+expected);
            require(devices.getDevice(b)==device && holder.electromagnetism$slots().getLast().identity().equals(secondId),"Held fuse protection destroyed holder or other attachment");
            var packet=be.getUpdateTag(level.registryAccess());require(packet.getCompound("FirstData").getBoolean("Broken"),"Held fuse event not applied to renderer data");
            for(int n=0;n<3;n++){stepWorld(sd,access);}
            packet=be.getUpdateTag(level.registryAccess());require(packet.getCompound("SecondData").getBoolean("NativeAvailable") && Math.abs(packet.getCompound("SecondData").getFloat("Light")-0.5)<0.001,"Held indicator did not consume native RMS");
            var saved=new CompoundTag();device.write(saved);device.read(saved);
            require(holder.electromagnetism$slots().getFirst().identity().equals(firstId),"Held attachment identity lost on reload");
            useFuseHolder(level,b,true,net.minecraft.world.item.ItemStack.EMPTY);useFuseHolder(level,b,true,com.george_vi.electroenergetics.CEEBlocks.CUT_OFF_SWITCH.asStack());
            var switchId=holder.electromagnetism$slots().getFirst().identity();require(!switchId.equals(firstId),"Replacement switch reused burned fuse identity");
            for(int mode=0;mode<2;mode++) {
                if(mode==1)useFuseHolder(level,b,true,net.minecraft.world.item.ItemStack.EMPTY);
                for(int n=0;n<3;n++){stepWorld(sd,access);}
                var reading=access.electromagnetism$reading(CeeNativeWorld.branchId(load,"main")).orElseThrow();
                require(Math.abs(reading.meanCurrent()-(mode==0?0:current))<1e-7,"Held switch command or port mapping failed");
                require(holder.electromagnetism$slots().getFirst().identity().equals(switchId),"Switch toggle reset physical identity");
            }
            useFuseHolder(level,b,true,com.simibubi.create.AllItems.WRENCH.asStack());useFuseHolder(level,b,true,net.minecraft.world.item.Items.COPPER_INGOT.getDefaultInstance());
            for(int n=0;n<3;n++){stepWorld(sd,access);}
            require(Math.abs(access.electromagnetism$reading(CeeNativeWorld.branchId(load,"main")).orElseThrow().meanCurrent()-10/(0.601+wireResistance))<1e-7,"Held copper bypass has wrong finite contact resistance");
            saved=new CompoundTag();device.write(saved);var invalid=saved.copy();invalid.putString("FirstID","electromagnetism:unknown_attachment");device.read(invalid);
            sd.ticker.tick();sd.ticker.endTick();require(access.electromagnetism$nativeStatus().status()==Status.UNSUPPORTED,"Unknown held attachment silently ignored");
            device.read(saved);
            access.electromagnetism$resumeMissingInputs();
            require(!be.getUpdateTag(level.registryAccess()).getCompound("SecondData").getBoolean("NativeAvailable"),"Invalid holder retained valid indicator light");
        } finally {for(var pos:java.util.List.of(a,b,c,d))devices.removeDevice(pos);level.removeBlock(b,false);}
        System.out.println("NATIVE_WORLD_FUSE_HOLDER_PASSED: all four actual item types, per-slot identity, analytic fuse trip, housing preservation, native indicator, switches, bypass and unknown attachment rejection");
    }

    private static void verifyFuse(net.minecraft.server.level.ServerLevel level,DevicesSavedData devices) {
        var sd=InfrastructureSavedData.load(level);var access=(CeeSimulationAccess)sd.ticker;
        var a=new BlockPos(10,250,8);var b=new BlockPos(12,250,8);
        try {
            level.getChunkAt(a);level.getChunkAt(b);
            level.setBlockAndUpdate(b,com.george_vi.electroenergetics.CEEBlocks.FUSE.getDefaultState());
            var fuse=devices.getDevice(b,com.george_vi.electroenergetics.content.fuse.FuseDevice.class);
            if(fuse==null)fuse=devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.FUSE.get(),b,new CompoundTag());
            fuse.setAmperage=10;fuse.temp=999999;
            sd.registerOrUpdateNodes(a,java.util.List.of(0,1));sd.registerOrUpdateNodes(b,java.util.List.of(0,1));
            double resistance=0.002;
            for(int terminal=0;terminal<2;terminal++) {
                var wire=com.george_vi.electroenergetics.simulation.infrastructure.WireData.ofLength(com.george_vi.electroenergetics.CEEWireTypes.STANDARD.get(),2);
                resistance+=wire.getResistance();
                sd.connect(new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(terminal,a),new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(terminal,b),wire);
            }
            var sourceTag=new CompoundTag();sourceTag.putDouble("Voltage",20*resistance);
            var source=devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_BATTERY.get(),a,sourceTag);
            double start=access.electromagnetism$saveNative().getDouble("AcceptedTime"),end=start;
            for(int n=0;n<100 && !fuse.isBroken;n++) {
                end=stepWorld(sd,access).getDouble("AcceptedTime");
            }
            require(fuse.isBroken,"Native fuse did not melt at twice rating: "+access.electromagnetism$nativeStatus());
            require(Math.abs(end-start+Math.log(0.75))<0.001,"Fuse trip time differs from lumped thermal analytic solution: "+(end-start));
            require(devices.getDevice(b)==fuse && com.george_vi.electroenergetics.CEEBlocks.BROKEN_FUSE.has(level.getBlockState(b)),"Fuse trip destroyed housing/identity instead of replacing wire");
            require(access.electromagnetism$reading(CeeNativeWorld.branchId(fuse,"main")).isEmpty(),"Melted fuse still publishes a conducting reading");
            source.voltage=0;
            for(int n=0;n<3;n++){stepWorld(sd,access);}
            require(fuse.isBroken,"Broken fuse spontaneously reclosed");
            fuse.isBroken=false;level.setBlockAndUpdate(b,com.george_vi.electroenergetics.CEEBlocks.FUSE.get().withPropertiesOf(level.getBlockState(b)));
            source.voltage=5*resistance;
            for(int n=0;n<8;n++){stepWorld(sd,access);}
            var repaired=access.electromagnetism$reading(CeeNativeWorld.branchId(fuse,"main")).orElseThrow();
            require(!fuse.isBroken && Math.abs(repaired.meanCurrent()-5)<1e-6,"Repaired fuse retained destroyed heat or failed to conduct");
            System.out.println("NATIVE_WORLD_FUSE_PASSED: analytic I-squared trip time, boundary interruption, broken housing and cold repair");
        } finally {devices.removeDevice(a);devices.removeDevice(b);level.removeBlock(b,false);}
    }

    private static void verifyEnergyMeters(net.minecraft.server.level.ServerLevel level,DevicesSavedData devices) throws Exception {
        var sd=InfrastructureSavedData.load(level);var access=(CeeSimulationAccess)sd.ticker;
        for(boolean three:new boolean[]{false,true}) {
            var meterPos=new BlockPos(10,250,8);
            var sourcePositions=java.util.List.of(new BlockPos(12,250,8),new BlockPos(14,250,8));
            var loadPositions=java.util.List.of(new BlockPos(12,250,10),new BlockPos(14,250,10));
            try {
                level.getChunkAt(meterPos);
                level.setBlockAndUpdate(meterPos,three?com.george_vi.electroenergetics.CEEBlocks.TRI_POLAR_ENERGY_METER.getDefaultState():com.george_vi.electroenergetics.CEEBlocks.ENERGY_METER.getDefaultState());
                var meter=devices.getDevice(meterPos);
                if(meter==null)meter=three?devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.TRI_POLAR_ENERGY_METER.get(),meterPos,new CompoundTag()):devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.ENERGY_METER.get(),meterPos,new CompoundTag());
                if(three)((com.george_vi.electroenergetics.content.energy_meter.TriPolarEnergyMeterDevice)meter).isClosed=true;
                else ((com.george_vi.electroenergetics.content.energy_meter.EnergyMeterDevice)meter).isClosed=true;
                var be=(com.george_vi.electroenergetics.content.energy_meter.EnergyMeterBlockEntity)level.getBlockEntity(meterPos);
                require(Double.isNaN(be.totalEnergy),"Unsolved energy meter displayed valid energy");
                sd.registerOrUpdateNodes(meterPos,three?java.util.List.of(0,1,2,3,4,5):java.util.List.of(0,1,2,3));
                for(int leg=0;leg<(three?2:1);leg++) {
                    var a=sourcePositions.get(leg);var b=loadPositions.get(leg);level.getChunkAt(a);level.getChunkAt(b);
                    var sourceTag=new CompoundTag();sourceTag.putDouble("Voltage",leg==0?10:-20);
                    var loadTag=new CompoundTag();loadTag.putDouble("Resistance",leg==0?5:20);
                    devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_BATTERY.get(),a,sourceTag);
                    devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_RESISTOR.get(),b,loadTag);
                    sd.registerOrUpdateNodes(a,java.util.List.of(0,1));sd.registerOrUpdateNodes(b,java.util.List.of(0,1));
                    int[] pins={leg==0?0:2,1,three?(leg==0?3:5):2,three?4:3};
                    for(int j=0;j<4;j++)sd.connect(new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(j%2,j<2?a:b),
                        new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(pins[j],meterPos),
                        com.george_vi.electroenergetics.simulation.infrastructure.WireData.ofLength(com.george_vi.electroenergetics.CEEWireTypes.STANDARD.get(),2));
                }
                for(int n=0;n<12;n++){stepWorld(sd,access);}
                require(Double.isFinite(be.activePower) && be.activePower>(three?35:18) && be.activePower<(three?41:21),"Meter reported contact loss instead of delivered power: "+be.activePower+" "+access.electromagnetism$nativeStatus());
                double expectedJoules=0;
                for(int leg=0;leg<(three?2:1);leg++) {
                    var r=access.electromagnetism$reading(CeeNativeWorld.meterBranchId(meter,leg)).orElseThrow();
                    expectedJoules+=r.totalAbsorbedEnergy()-r.totalReturnedEnergy();
                }
                require(expectedJoules>0 && Math.abs(be.totalEnergy*3_600_000-expectedJoules)<1e-9,"Meter display did not use native lifetime SI energy");
                var nativeMeter=(com.electromagnetism.integration.cee.nativebridge.NativeEnergyMeter)meter;
                UUID before=nativeMeter.electromagnetism$counterIdentity();
                var persisted=new CompoundTag();meter.write(persisted);meter.read(persisted);
                require(before.equals(nativeMeter.electromagnetism$counterIdentity()),"Energy counter changed across parameter persistence");
                var all=devices.save(new CompoundTag(),level.registryAccess());
                var loader=DevicesSavedData.class.getDeclaredMethod("load",net.minecraft.server.level.ServerLevel.class,CompoundTag.class,net.minecraft.core.HolderLookup.Provider.class);loader.setAccessible(true);
                var reloaded=(DevicesSavedData)loader.invoke(null,level,all,level.registryAccess());
                require(before.equals(((NativeEnergyMeter)reloaded.getDevice(meterPos)).electromagnetism$counterIdentity()),"Energy counter changed across actual device save/load");
                for(boolean missing:new boolean[]{true,false}) {
                    var corrupt=all.copy();
                    for(var item:corrupt.getList("Devices",net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                        var envelope=(CompoundTag)item;
                        if(DevicesSavedData.readBlockPos(envelope,"Pos").equals(meterPos)) {
                            if(missing)envelope.getCompound("Data").remove("NativeCounter");
                            else envelope.getCompound("Data").putString("NativeCounter","corrupt-counter");
                        }
                    }
                    var invalid=(DevicesSavedData)loader.invoke(null,level,corrupt,level.registryAccess());
                    var invalidMeter=(NativeEnergyMeter)invalid.getDevice(meterPos);
                    require(!invalidMeter.electromagnetism$counterProblem().isBlank(),"Missing/corrupt counter silently reset energy");
                    var retained=new CompoundTag();invalid.getDevice(meterPos).write(retained);
                    require(!retained.hasUUID("NativeCounter"),"Invalid counter identity silently repaired on save");
                }
                var player=net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level);
                be.owner=UUID.randomUUID();
                new com.george_vi.electroenergetics.content.energy_meter.ChangeEnergyMeterStatePacket(true,false,meterPos).handle(player);
                require(before.equals(nativeMeter.electromagnetism$counterIdentity()),"Unauthorized player reset native energy counter");
                be.owner=player.getUUID();
                new com.george_vi.electroenergetics.content.energy_meter.ChangeEnergyMeterStatePacket(true,false,meterPos).handle(player);
                require(!before.equals(nativeMeter.electromagnetism$counterIdentity()),"Authorized reset did not replace counter identity");
                stepWorld(sd,access);
                require(Double.isFinite(be.totalEnergy) && be.totalEnergy*3_600_000<expectedJoules/2,"Native counter reset replayed prior energy");
                new com.george_vi.electroenergetics.content.energy_meter.ChangeEnergyMeterStatePacket(false,true,meterPos).handle(player);
                for(int n=0;n<3;n++){stepWorld(sd,access);}
                require(Double.isFinite(be.activePower) && Math.abs(be.activePower)<1e-7,"Meter disconnect did not open physical contacts: three="+three+" power="+be.activePower+" status="+access.electromagnetism$nativeStatus());
            } finally {
                devices.removeDevice(meterPos);level.removeBlock(meterPos,false);
                for(var p:sourcePositions)devices.removeDevice(p);
                for(var p:loadPositions)devices.removeDevice(p);
            }
        }
        System.out.println("NATIVE_WORLD_ENERGY_METERS_PASSED: single and unbalanced three-wire power, lifetime joules, authorized reset and disconnect");
    }

    private static void verifyWorkingCircuit(net.minecraft.server.level.ServerLevel level,DevicesSavedData devices) throws Exception {
        var a=new BlockPos(10,250,8);var b=new BlockPos(12,250,8);
        level.getChunkAt(a);level.getChunkAt(b);
        var sd=InfrastructureSavedData.load(level);var access=(CeeSimulationAccess)sd.ticker;
        try {
            require(devices.getDevice(a)==null && devices.getDevice(b)==null,"Working circuit fixture occupied");
            var sourceTag=new CompoundTag();sourceTag.putDouble("Voltage",10.0);
            var loadTag=new CompoundTag();loadTag.putDouble("Resistance",5.0);
            var source=devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_BATTERY.get(),a,sourceTag);
            var load=devices.addDevice(com.george_vi.electroenergetics.CEESimulatedDevices.CREATIVE_RESISTOR.get(),b,loadTag);
            sd.registerOrUpdateNodes(a,java.util.List.of(0,1));sd.registerOrUpdateNodes(b,java.util.List.of(0,1));
            var first=com.george_vi.electroenergetics.simulation.infrastructure.WireData.ofLength(com.george_vi.electroenergetics.CEEWireTypes.STANDARD.get(),2);
            var second=com.george_vi.electroenergetics.simulation.infrastructure.WireData.ofLength(com.george_vi.electroenergetics.CEEWireTypes.STANDARD.get(),2);
            var a0=new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(0,a);var a1=new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(1,a);
            var b0=new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(0,b);var b1=new com.george_vi.electroenergetics.foundation.nodes.InWorldNode(1,b);
            sd.connect(a0,b0,first);sd.connect(a1,b1,second);
            UUID loadBranch=CeeNativeWorld.branchId(load,"main");
            for(int i=0;i<20 && access.electromagnetism$reading(loadBranch).isEmpty();i++) {
                stepWorld(sd,access);
            }
            var observed=access.electromagnetism$reading(loadBranch);require(observed.isPresent(),"Native world produced no reading: "+access.electromagnetism$nativeStatus());var reading=observed.orElseThrow();
            double expected=10/(5+0.001+first.getResistance()+second.getResistance());
            require(Math.abs(reading.meanCurrent()-expected)<1e-7,"World series current differs from analytic reference");
            require(Math.abs(reading.activePower()-5*expected*expected)<1e-6,"World active power differs from analytic reference");
            require(sd.ticker.lastResults==null,"World backend published legacy arrays");
            var state=access.electromagnetism$saveNative();require(state.getDouble("AcceptedTime")>0,"World snapshot has no accepted time");
            var stored=sd.save(new CompoundTag(),level.registryAccess());
            var method=InfrastructureSavedData.class.getDeclaredMethod("load",net.minecraft.server.level.ServerLevel.class,CompoundTag.class);method.setAccessible(true);
            var restored=(InfrastructureSavedData)method.invoke(null,level,stored);
            // Public load(Level) does this after decoding NBT. The private
            // decoder alone leaves the conductor geometry empty.
            var geometry=InfrastructureSavedData.class.getDeclaredMethod("loadSimulationState");geometry.setAccessible(true);
            geometry.invoke(restored);
            var connection=new com.george_vi.electroenergetics.foundation.nodes.InWorldNodeConnection(a0,b0);
            require(((CeeWireIdentity)first).electromagnetism$wireIdentity().equals(((CeeWireIdentity)restored.getConnectionData(connection)).electromagnetism$wireIdentity()),"Wire incarnation changed on save/load");
            for(int invalidKind=0;invalidKind<3;invalidKind++) {
                var corrupt=stored.copy();
                boolean changed=false;
                for(var item:corrupt.getList("Nodes",net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                    for(var other:((CompoundTag)item).getList("ConnectedNodes",net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                        var entry=(CompoundTag)other;
                        if(invalidKind==0)entry.remove("ElectromagnetismIdentity");
                        else if(invalidKind==1)entry.putString("ElectromagnetismIdentity","malformed");
                        else entry.putUUID("ElectromagnetismIdentity",UUID.randomUUID());
                        changed=true;break;
                    }
                    if(changed)break;
                }
                require(changed,"Identity corruption fixture has no wires");
                var invalid=(InfrastructureSavedData)method.invoke(null,level,corrupt);
                geometry.invoke(invalid);
                require(!new CeeNativeWorld(invalid).capture().blockers().isEmpty(),"Invalid wire identity was silently replaced: "+invalidKind);
            }
            access.electromagnetism$closeNative();
            sd.ticker=new com.george_vi.electroenergetics.simulation.simulator.SimulationTicker(level,sd);
            access=(CeeSimulationAccess)sd.ticker;access.electromagnetism$restoreNative(state);
            var resumed=state;
            for(int attempt=0;attempt<20 && resumed.getDouble("AcceptedTime")<=state.getDouble("AcceptedTime");attempt++) {
                resumed=stepWorld(sd,access);
            }
            require(resumed.getDouble("AcceptedTime")>state.getDouble("AcceptedTime"),"World snapshot did not resume accepted physical time: "+access.electromagnetism$nativeStatus());
            devices.removeDevice(b);
            level.setBlockAndUpdate(b,com.electromagnetism.registry.ModBlocks.LIGHT_FIXTURE.getDefaultState());
            var lamp=devices.getDevice(b,com.electromagnetism.content.lighting.fixture.LightFixtureDevice.class);
            if(lamp==null)lamp=devices.addDevice(ModDeviceTypes.LIGHT_FIXTURE.get(),b,new CompoundTag());
            lamp.setInstalledBulb(com.electromagnetism.registry.ModItems.LV_LIGHT_BULB.get(),null);
            sd.registerOrUpdateNodes(b,java.util.List.of(0,1));
            sd.connect(a0,b0,com.george_vi.electroenergetics.simulation.infrastructure.WireData.ofLength(com.george_vi.electroenergetics.CEEWireTypes.STANDARD.get(),2));
            sd.connect(a1,b1,com.george_vi.electroenergetics.simulation.infrastructure.WireData.ofLength(com.george_vi.electroenergetics.CEEWireTypes.STANDARD.get(),2));
            source.voltage=200;
            boolean lit=false;
            for(int attempt=0;attempt<500 && !lamp.isBurned();attempt++) {
                stepWorld(sd,access);
                lit |= level.getBlockState(b).getValue(com.electromagnetism.content.lighting.fixture.LightFixtureBlock.POWER)>0;
            }
            require(lit,"Accepted native lamp never illuminated: "+access.electromagnetism$nativeStatus());
            require(lamp.isBurned(),"Native lamp protection did not apply to world: "+access.electromagnetism$nativeStatus());
            require(level.getBlockState(b).getBlock()==com.electromagnetism.registry.ModBlocks.LIGHT_FIXTURE.get(),"Bulb protection destroyed housing");
            require(level.getBlockState(b).getValue(com.electromagnetism.content.lighting.fixture.LightFixtureBlock.POWER)==0,"Burned lamp remained lit");
            System.out.println("NATIVE_WORLD_LAMP_PASSED: accepted temperature, emitted light, event-boundary burnout and housing preservation");
            devices.removeDevice(b);level.removeBlock(b,false);
            level.setBlockAndUpdate(b,com.electromagnetism.registry.ModBlocks.FACTORY_LIGHT.getDefaultState());
            var factory=devices.getDevice(b,com.electromagnetism.content.lighting.factory.FactoryLightDevice.class);
            if(factory==null)factory=devices.addDevice(ModDeviceTypes.FACTORY_LIGHT.get(),b,new CompoundTag());
            factory.setInstalledBulb(com.electromagnetism.registry.ModItems.LV_LIGHT_BULB.get(),null);
            sd.registerOrUpdateNodes(b,java.util.List.of(0,1));
            sd.connect(a0,b0,com.george_vi.electroenergetics.simulation.infrastructure.WireData.ofLength(com.george_vi.electroenergetics.CEEWireTypes.STANDARD.get(),2));
            sd.connect(a1,b1,com.george_vi.electroenergetics.simulation.infrastructure.WireData.ofLength(com.george_vi.electroenergetics.CEEWireTypes.STANDARD.get(),2));
            boolean projected=false;
            for(int attempt=0;attempt<500 && !factory.isBurned();attempt++) {
                stepWorld(sd,access);
                projected |= level.getBlockState(b).getValue(com.electromagnetism.content.lighting.factory.FactoryLightBlock.POWER)>1;
            }
            require(projected && factory.isBurned(),"Factory light native thermal path failed: "+access.electromagnetism$nativeStatus());
            devices.removeDevice(b);level.removeBlock(b,false);
            level.setBlockAndUpdate(b,com.electromagnetism.registry.ModBlocks.HEATING_COIL.getDefaultState());
            var heater=devices.getDevice(b,com.electromagnetism.content.thermal.heater.HeatingCoilDevice.class);
            if(heater==null)heater=devices.addDevice(ModDeviceTypes.HEATING_COIL.get(),b,new CompoundTag());
            sd.registerOrUpdateNodes(b,java.util.List.of(0,1));
            sd.connect(a0,b0,com.george_vi.electroenergetics.simulation.infrastructure.WireData.ofLength(com.george_vi.electroenergetics.CEEWireTypes.STANDARD.get(),2));
            sd.connect(a1,b1,com.george_vi.electroenergetics.simulation.infrastructure.WireData.ofLength(com.george_vi.electroenergetics.CEEWireTypes.STANDARD.get(),2));
            source.voltage=2000;
            var heaterBe=(com.electromagnetism.content.thermal.heater.HeatingCoilBlockEntity)level.getBlockEntity(b);
            boolean smoking=false,blasting=false;
            for(int attempt=0;attempt<500 && !heater.nativeBurned();attempt++) {
                stepWorld(sd,access);
                smoking |= heaterBe.getState()==com.electromagnetism.content.thermal.heater.HeatingCoilBlockEntity.State.SMOKING;
                blasting |= heaterBe.getState()==com.electromagnetism.content.thermal.heater.HeatingCoilBlockEntity.State.BLASTING;
            }
            require(smoking && blasting && heater.nativeBurned(),"Native heater did not traverse processing states and protection: "+access.electromagnetism$nativeStatus());
            require(heaterBe.getState()==com.electromagnetism.content.thermal.heater.HeatingCoilBlockEntity.State.COLD,"Burned coil still grants processing heat");
            System.out.println("NATIVE_WORLD_HEAT_PASSED: factory light and heater accepted feedback, processing states and protection");
            System.out.println("NATIVE_WORLD_CIRCUIT_PASSED: source, two finite wires, load, accepted power, wire identity and physical-state restore");
        } finally {devices.removeDevice(a);devices.removeDevice(b);level.removeBlock(b,false);}
    }
    /** Physical model fixtures edit the world only after draining the accepted input boundary.
     * Cross-tick scheduling and backpressure have separate coordinator regressions. */
    /** Return each accepted prefix so frequency-domain fixtures integrate every observation window. */
    private static CompoundTag advanceWorld(InfrastructureSavedData sd,CeeSimulationAccess access) {
        if(access.electromagnetism$nativeProgress().map(p->p.backlogSeconds()<1e-9).orElse(true))sd.ticker.tick();
        var snapshot=access.electromagnetism$saveNative();sd.ticker.endTick();return snapshot;
    }
    private static CompoundTag stepWorld(InfrastructureSavedData sd,CeeSimulationAccess access) {
        long confirmed=access.electromagnetism$saveNative().getLong("ConfirmedEvent");
        sd.ticker.tick();
        return drainWorld(sd,access,confirmed);
    }
    private static CompoundTag drainWorld(InfrastructureSavedData sd,CeeSimulationAccess access) {
        return drainWorld(sd,access,-1);
    }
    private static CompoundTag drainWorld(InfrastructureSavedData sd,CeeSimulationAccess access,long confirmed) {
        CompoundTag snapshot=null,eventBoundary=null;
        for(int attempt=0;attempt<2000;attempt++) {
            snapshot=access.electromagnetism$saveNative();sd.ticker.endTick();
            // Preserve the actual trip time for analytic event tests while draining
            // the already-disconnected remainder before another fixture edits the world.
            if(confirmed>=0 && eventBoundary==null && snapshot.getLong("ConfirmedEvent")>confirmed)eventBoundary=snapshot;
            var progress=access.electromagnetism$nativeProgress().orElseThrow();
            require(progress.pauseReason().isEmpty(),"Physical fixture paused before its accepted boundary: "+access.electromagnetism$nativeStatus());
            if(progress.backlogSeconds()<1e-9)return eventBoundary==null?snapshot:eventBoundary;
        }
        throw new AssertionError("Physical fixture failed to drain bounded input: "+access.electromagnetism$nativeStatus());
    }
    private static void require(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
    private static void verifyIdentityPersistence(DevicesSavedData devices, net.minecraft.server.level.ServerLevel level) throws Exception {
        var position = new BlockPos(9,250,8);
        require(devices.getDevice(position) == null, "Identity fixture position occupied");
        try {
            var original = devices.addDevice(ModDeviceTypes.NPN_TRANSISTOR.get(), position, new CompoundTag());
            var identity = ((CeeDeviceIdentity)original).electromagnetism$nativeIdentity();
            var all = devices.save(new CompoundTag(), level.registryAccess());
            var entries = new net.minecraft.nbt.ListTag();
            for (var entry : all.getList("Devices",net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                var envelope = (CompoundTag)entry;
                if (DevicesSavedData.readBlockPos(envelope,"Pos").equals(position)) entries.add(envelope.copy());
            }
            require(entries.size() == 1, "Saved identity fixture missing");
            var envelope = entries.getCompound(0);
            require(envelope.contains("ElectromagnetismIdentity"), "Identity not persisted outside device parameters");
            var saved = new CompoundTag();saved.put("Devices",entries);
            var load = DevicesSavedData.class.getDeclaredMethod("load",net.minecraft.server.level.ServerLevel.class,
                    CompoundTag.class, net.minecraft.core.HolderLookup.Provider.class);
            load.setAccessible(true);
            var restored = (DevicesSavedData)load.invoke(null,level,saved,level.registryAccess());
            var restoredDevice = restored.getDevice(position);
            require(identity.equals(((CeeDeviceIdentity)restoredDevice).electromagnetism$nativeIdentity()),"Identity changed on save/load");
            var absent=saved.copy();absent.getList("Devices",net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).remove("ElectromagnetismIdentity");
            var missing=(DevicesSavedData)load.invoke(null,level,absent,level.registryAccess());
            require(!((CeeDeviceIdentity)missing.getDevice(position)).electromagnetism$identityProblem().isBlank(),"Missing persisted identity silently replaced");
            restoredDevice.read(new CompoundTag());
            require(identity.equals(((CeeDeviceIdentity)restoredDevice).electromagnetism$nativeIdentity()),"Parameter read replaced identity");
            envelope.putString("ElectromagnetismIdentity","corrupt-identity-must-survive");
            var malformed = (DevicesSavedData)load.invoke(null,level,saved,level.registryAccess());
            require(malformed.getDevice(position) != null,"Corrupt identity deleted device");
            require(CeeInputInventory.capture(malformed).blockers().stream().anyMatch(s->s.contains("Malformed native device identity")),"Corrupt identity not blocked");
            var retained = malformed.save(new CompoundTag(),level.registryAccess()).getList("Devices",net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0);
            require(retained.getString("ElectromagnetismIdentity").equals("corrupt-identity-must-survive"),"Corrupt identity data lost");
            devices.removeDevice(position);
            var replacement = devices.addDevice(ModDeviceTypes.NPN_TRANSISTOR.get(),position,new CompoundTag());
            require(!identity.equals(((CeeDeviceIdentity)replacement).electromagnetism$nativeIdentity()),"New placement reused saved identity");
        } finally { devices.removeDevice(position); }
    }
    private static final class Probe extends SimpleElectricalDevice {
        int initialized, pre, post;
        Probe(Level l, BlockPos p, DevicesSavedData d, SimulatedDeviceType<?> t) { super(l,p,d,t); }
        @Override public void initialize() { initialized++; }
        @Override public void preTick(BridgeCollector bridges) { pre++; throw new AssertionError("Legacy preTick"); }
        @Override public void postTick(SimulationResults results) { post++; throw new AssertionError("Legacy postTick"); }
    }
}
