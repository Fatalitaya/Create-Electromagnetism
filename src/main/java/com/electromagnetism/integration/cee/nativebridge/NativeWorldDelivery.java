package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.*;
import com.electromagnetism.integration.cee.display.RmsDisplayWindow;
import com.electromagnetism.config.ModConfigs;
import com.electromagnetism.content.thermal.heater.HeatingCoilDevice;
import com.george_vi.electroenergetics.devices.device.SimulatedDevice;
import com.george_vi.electroenergetics.foundation.nodes.*;
import com.george_vi.electroenergetics.simulation.infrastructure.*;
import net.minecraft.nbt.*;
import java.util.*;
import static com.electromagnetism.simulation.SimulationProtocol.*;

import static com.electromagnetism.integration.cee.nativebridge.CeeNativeWorld.branchId;
import static com.electromagnetism.integration.cee.nativebridge.CeeNativeWorld.meterBranchId;

final class NativeWorldDelivery implements SimulationCoordinator.DeliverySink {
    private final InfrastructureSavedData infrastructure;
    private final NativePanels panels;
    private final NativeAlternators alternators;
    private final NativeEnvironment environment;
    private final NativeTrains trains;
    private final Map<UUID,InWorldNodeConnection> wiresByBranch;
    private final java.util.function.Supplier<SimulationCoordinator.Frame> capture;
    private final Map<UUID,Reading> readings=new HashMap<>();
    private record EventKey(UUID session,UUID event) {}
    private final Set<EventKey> confirmedEvents=new HashSet<>();
    NativeWorldDelivery(InfrastructureSavedData infrastructure,NativePanels panels,NativeAlternators alternators,NativeEnvironment environment,NativeTrains trains,Map<UUID,InWorldNodeConnection> wiresByBranch,java.util.function.Supplier<SimulationCoordinator.Frame> capture) {
        this.infrastructure=infrastructure;
        this.panels=panels;
        this.alternators=alternators;
        this.environment=environment;
        this.trains=trains;
        this.wiresByBranch=wiresByBranch;
        this.capture=capture;
    }
    Optional<Reading> reading(UUID branch){return Optional.ofNullable(readings.get(branch));}
    private final Map<UUID,RmsDisplayWindow> displayWindows = new HashMap<>();
    private RmsDisplayWindow.Sample displaySample(Reading reading) {
        return displayWindows.computeIfAbsent(reading.branch(), id -> new RmsDisplayWindow()).accept(reading);
    }
    OptionalDouble displayCurrent(UUID branch) {
        var window = displayWindows.get(branch);
        return window == null || window.sample() == null || !readings.containsKey(branch)
                ? OptionalDouble.empty() : OptionalDouble.of(window.sample().current());
    }
    void clear(){readings.clear();displayWindows.clear();}
    void invalidateFaultedRecipients(SimulationCoordinator.Frame latest,Set<DeviceId> faulted) {
        invalidateMembers(latest,faulted,true,true);
    }
    void invalidateElectrical(SimulationCoordinator.Frame latest,Set<DeviceId> members) {
        invalidateMembers(latest,members,false,false);
    }
    private void invalidateMembers(SimulationCoordinator.Frame latest,Set<DeviceId> faulted,boolean clearInstrumentHistory,boolean revokeMechanical) {
        if(faulted.isEmpty())return;
        if(revokeMechanical && latest!=null)for(var assembly:latest.recipients().assemblies())if(faulted.contains(new DeviceId(assembly.identity(),0))) {
            for(UUID member:assembly.members()) {
                Long position=latest.recipients().positions().get(new DeviceId(member,0));
                if(position!=null && infrastructure.level.getBlockEntity(net.minecraft.core.BlockPos.of(position)) instanceof NativeRotorBoundary rotor
                        && rotor.electromagnetism$rotorIdentity().equals(member))rotor.electromagnetism$torque(0,false);
            }
        }
        clearReadings(latest,faulted);
        panels.invalidate(faulted);
        invalidateDevices(clearInstrumentHistory,revokeMechanical,faulted);
        trains.invalidate(infrastructure,faulted,revokeMechanical);
    }
    private void clearReadings(SimulationCoordinator.Frame latest,Set<DeviceId> faulted) {
        if(latest!=null)latest.recipients().branches().forEach((branch,owner)->{if(faulted.contains(owner))readings.remove(branch);});
        if(latest!=null)for(var branch:latest.branches())
            if(faulted.contains(branch.positive().device()))readings.remove(branch.id());
        displayWindows.keySet().retainAll(readings.keySet());
    }
    void invalidateForFrame(SimulationCoordinator.Frame latest,SimulationCoordinator.Frame frame) {
        boolean changedBindings=!SimulationCoordinator.sameBindings(frame,latest)
                || latest==null || !frame.waveforms().equals(latest.waveforms());
        alternators.revokeChanged(infrastructure,latest,frame);
        if(latest!=null)for(var entry:latest.recipients().mechanicalNetworks().entrySet()) {
            var id=entry.getKey();
            if(!Objects.equals(entry.getValue(),frame.recipients().mechanicalNetworks().get(id))) {
                var device=resolve(latest.recipients(),id);
                if(device!=null && infrastructure.level.getBlockEntity(device.pos) instanceof NativeMotorBoundary motor)motor.electromagnetism$motor(0,0,0,false);
            }
        }
        // A changed input does not undo an accepted observation. In particular,
        // shaft speed changes after Create consumes the accepted motor output;
        // clearing here erased every independent circuit at the end of each tick.
        // Keep the last accepted window until its replacement arrives. Topology,
        // subscriptions and unavailable inputs still revoke presentation data.
        if(changedBindings || !frame.blockers().equals(latest.blockers())
                || !frame.recipients().equals(latest.recipients())) {
            var changed=FaultDomainManager.changedBindings(latest,frame);
            if(latest!=null)clearReadings(latest,changed);
            invalidateMembers(frame,changed,changedBindings,false);
        }
    }
    private void invalidateDevices(boolean clearInstrumentHistory,boolean revokeMechanical,Set<DeviceId> faulted) {
        for(var device:infrastructure.deviceSD.getDevices()) {
            if(faulted!=null && (!(device instanceof CeeDeviceIdentity identity) || !faulted.contains(identity.electromagnetism$nativeIdentity())))continue;
            if(!infrastructure.level.getChunkSource().hasChunk(device.pos.getX()>>4,device.pos.getZ()>>4))continue;
            if(infrastructure.level.getBlockEntity(device.pos) instanceof NativeConverterBoundary converter)converter.electromagnetism$displayPower(0);
            if(infrastructure.level.getBlockEntity(device.pos) instanceof NativePumpBoundary pump)pump.electromagnetism$displayPower(OptionalDouble.empty());
            if(infrastructure.level.getBlockEntity(device.pos) instanceof NativeRegulatorDisplay regulator)regulator.electromagnetism$displayRegulator(0,0,0);
            if(infrastructure.level.getBlockEntity(device.pos) instanceof com.electromagnetism.content.electromechanics.excitation.ExcitationStatorBlockEntity field)field.nativeUnavailable();
            if(device instanceof NativeFuseHolder holder)holder.electromagnetism$slotsUnavailable();
            if(infrastructure.level.getBlockEntity(device.pos) instanceof NativeCellDisplay cell)cell.electromagnetism$cellsUnavailable();
            if(revokeMechanical && infrastructure.level.getBlockEntity(device.pos) instanceof NativeMotorBoundary motor)motor.electromagnetism$motor(0,0,0,false);
            if(infrastructure.level.getBlockEntity(device.pos) instanceof NativeHvDisplay hv)hv.electromagnetism$arc(0,false);
            if(infrastructure.level.getBlockEntity(device.pos) instanceof NativeSynchroscopeDisplay sync)sync.electromagnetism$synchroscope(Double.NaN,false);
            if(infrastructure.level.getBlockEntity(device.pos) instanceof NativeFrequencyDisplay frequency)frequency.electromagnetism$frequency(java.util.OptionalDouble.empty());
            if(device instanceof NativeLamp lamp)lamp.nativeUnavailable();
            else if(device instanceof HeatingCoilDevice heater)heater.nativeUnavailable();
            if(infrastructure.level.getBlockEntity(device.pos) instanceof com.electromagnetism.content.acoustics.bell.ElectricBellBlockEntity bell)bell.nativeUnavailable();
            if(infrastructure.level.getBlockEntity(device.pos) instanceof NativeIndicatorDisplay indicator)indicator.electromagnetism$indicators(0,0,false);
            if(infrastructure.level.getBlockEntity(device.pos) instanceof NativeBuzzerDisplay buzzer)buzzer.electromagnetism$buzzer(0,false);
            if(infrastructure.level.getBlockEntity(device.pos) instanceof NativeBulbDisplay bulb)bulb.electromagnetism$setNativeLight(0,false);
            if(infrastructure.level.getBlockEntity(device.pos) instanceof NativeHeaterDisplay heater)heater.electromagnetism$heatUnavailable();
            if(infrastructure.level.getBlockEntity(device.pos) instanceof NativeGauge gauge)gauge.electromagnetism$gaugeUnavailable();
            if(infrastructure.level.getBlockEntity(device.pos) instanceof NativeTransformerDisplay transformer)transformer.electromagnetism$transformerUnavailable();
            if(infrastructure.level.getBlockEntity(device.pos) instanceof NativeEnergyDisplay meter)meter.electromagnetism$energyUnavailable();
            // A new input boundary invalidates instantaneous displays. Previously
            // accepted waveform samples remain valid history and playable audio.
            if(clearInstrumentHistory && infrastructure.level.getBlockEntity(device.pos) instanceof com.electromagnetism.content.instruments.InstrumentBlockEntity instrument)instrument.nativeUnavailable();
        }
    }
    private SimulatedDevice resolve(WorldRecipients recipients,DeviceId id) {
        Long position=recipients.positions().get(id);if(position==null)return null;
        var device=infrastructure.deviceSD.getDevice(net.minecraft.core.BlockPos.of(position));
        return device instanceof CeeDeviceIdentity identity && identity.electromagnetism$nativeIdentity().equals(id)?device:null;
    }
    @Override public List<UUID> apply(SimulationCoordinator.Frame historical,SimulationCoordinator.Frame live,Observations observations,java.util.function.BiConsumer<List<UUID>,SimulationCoordinator.Frame> destroyed) {
        var devicesByBranch=new HashMap<UUID,SimulatedDevice>();
        historical.recipients().branches().forEach((branch,id)->{var device=resolve(historical.recipients(),id);if(device!=null)devicesByBranch.put(branch,device);});
        var wiresByBranch=new HashMap<UUID,InWorldNodeConnection>();
        historical.recipients().wires().forEach((id,target)->{
            if(target.equals(live.recipients().wires().get(id)) && this.wiresByBranch.containsKey(id))wiresByBranch.put(id,this.wiresByBranch.get(id));
        });
        var damageAssemblies=new IdentityHashMap<SimulatedDevice,List<SimulatedDevice>>();
        historical.recipients().damageGroups().forEach((owner,members)->{
            var device=resolve(historical.recipients(),owner);
            if(device!=null)damageAssemblies.put(device,members.stream().map(id->resolve(historical.recipients(),id)).filter(Objects::nonNull).toList());
        });
        var beforeEvents=observations.events().isEmpty()?List.<Branch>of():historical.branches();
        var currentBranches=live.branches().stream().map(Branch::id).collect(java.util.stream.Collectors.toSet());
        historical.recipients().wires().keySet().stream().filter(id->!wiresByBranch.containsKey(id)).forEach(currentBranches::remove);
        var confirmed=new ArrayList<UUID>();
        // Validate the entire event boundary before applying its first effect.
        if(!observations.events().isEmpty() && !live.blockers().isEmpty())
            throw new IllegalStateException("Cannot resolve protection targets: "+live.blockers());
        for(var event:observations.events())if(!confirmedEvents.contains(new EventKey(observations.accepted().revision().session(),event.id()))) {
            if(!currentBranches.contains(event.branch()))continue; // Explicitly retired captured target.
            var owner=devicesByBranch.get(event.branch());
            if(owner==null && historical.recipients().branches().containsKey(event.branch()))continue; // Original instance retired; never target its replacement.
            if(event.action()!=EventAction.DAMAGE && !(owner instanceof com.george_vi.electroenergetics.content.relay.RelayDevice))
                throw new IllegalStateException("Missing relay event consumer: "+event.branch());
            if(owner instanceof NativeFuseHolder holder && holder.electromagnetism$slots().stream()
                    .noneMatch(slot->event.branch().equals(branchId(owner,"fuse-holder/"+slot.identity()))))
                throw new IllegalStateException("Missing captured fuse slot: "+event.branch());
            if(event.action()==EventAction.DAMAGE && owner==null && !wiresByBranch.containsKey(event.branch()) && !panels.owns(event.branch()))
                throw new IllegalStateException("Missing protection event consumer: "+event.branch());
        }
        environment.apply(infrastructure,observations,historical.recipients().effects());

        if(observations.accepted().end()>observations.accepted().start()) {
            historical.branches().forEach(b->readings.remove(b.id()));for(var reading:observations.readings()) {
                panels.accept(reading,this::displaySample);
                if(wiresByBranch.containsKey(reading.branch()))displaySample(reading);
                readings.put(reading.branch(),reading);
                var owner=devicesByBranch.get(reading.branch());
                if(owner instanceof com.george_vi.electroenergetics.content.transmission_distribution.hv_switch.HVSwitchDevice
                        && infrastructure.level.getBlockEntity(owner.pos) instanceof NativeHvDisplay hv)hv.electromagnetism$arc(reading.temperatureKelvin().orElse(0),reading.temperatureKelvin().isPresent());
                if(owner instanceof com.george_vi.electroenergetics.content.frequency_meter.FrequencyMeterDevice && infrastructure.level.getBlockEntity(owner.pos) instanceof NativeFrequencyDisplay frequency)frequency.electromagnetism$frequency(reading.frequency());
                if(owner instanceof com.george_vi.electroenergetics.content.transmission_distribution.sf6_breaker.SF6BreakerDevice breaker) {
                    boolean closed=historical.branches().stream().filter(branch->branch.id().equals(reading.branch())).findFirst().orElseThrow().parameters().get("ambientOhms")<1;
                    if(breaker.closed!=closed)com.george_vi.electroenergetics.CEESoundEvents.playOnServer(infrastructure.level,breaker.pos,com.george_vi.electroenergetics.CEESoundEvents.SF6_TRIP.get(),1,1);
                    breaker.closed=closed;
                    var other=infrastructure.deviceSD.getDevice(breaker.pos.above(),com.george_vi.electroenergetics.content.transmission_distribution.sf6_breaker.SF6BreakerDevice.class);
                    if(other!=null)other.closed=closed;
                }
                if(owner instanceof NativeFuseHolder holder)for(var slot:holder.electromagnetism$slots()) {
                    if(reading.branch().equals(branchId(owner,"fuse-holder/"+slot.identity())))holder.electromagnetism$slotReading(slot.identity(),reading);
                }
                if(owner instanceof com.george_vi.electroenergetics.content.buzzer.BuzzerDevice && infrastructure.level.getBlockEntity(owner.pos) instanceof NativeBuzzerDisplay buzzer)buzzer.electromagnetism$buzzer(reading.rmsVoltage(),true);
                if(owner instanceof com.george_vi.electroenergetics.content.bulb.BulbDevice bulb && infrastructure.level.getBlockEntity(owner.pos) instanceof NativeBulbDisplay display)
                    display.electromagnetism$setNativeLight((reading.temperatureKelvin().orElse(293.15)-293.15)/300,!bulb.destroyed && reading.temperatureKelvin().isPresent());
                if(owner instanceof com.george_vi.electroenergetics.content.resistive_heater.ResistiveHeaterDevice
                        && infrastructure.level.getBlockEntity(owner.pos) instanceof NativeHeaterDisplay heater)heater.electromagnetism$acceptHeat(reading);
                if(owner instanceof com.electromagnetism.content.acoustics.bell.ElectricBellDevice && reading.branch().equals(branchId(owner,"main"))
                        && infrastructure.level.getBlockEntity(owner.pos) instanceof com.electromagnetism.content.acoustics.bell.ElectricBellBlockEntity bell)bell.acceptNative(reading);
                if(devicesByBranch.get(reading.branch()) instanceof NativeLamp lamp)lamp.acceptNative(reading);
                else if(devicesByBranch.get(reading.branch()) instanceof HeatingCoilDevice heater)heater.acceptNative(reading);
                if(devicesByBranch.get(reading.branch()) instanceof com.george_vi.electroenergetics.content.gauge.GaugeDevice gauge
                        && infrastructure.level.getBlockEntity(gauge.pos) instanceof NativeGauge consumer)consumer.electromagnetism$acceptGauge(reading,displaySample(reading));
            }
        }
        for(var device:new HashSet<>(devicesByBranch.values())) {
            if(device instanceof com.george_vi.electroenergetics.content.converter.ConverterDevice converter
                    && infrastructure.level.getBlockEntity(device.pos) instanceof NativeConverterBoundary display) {
                display.electromagnetism$displayPower(converterPower(converter));
            }
            if(device instanceof com.george_vi.electroenergetics.content.electric_pump.ElectricPumpDevice
                    && infrastructure.level.getBlockEntity(device.pos) instanceof NativePumpBoundary display) {
                display.electromagnetism$displayPower(NativeFluidPump.inputPower(device,readings));
            }
            if(device instanceof com.george_vi.electroenergetics.content.transmission_distribution.voltage_regulator.VoltageRegulatorDevice regulator && regulator.bottom
                    && infrastructure.level.getBlockEntity(device.pos) instanceof NativeRegulatorDisplay display) {
                var input=readings.get(branchId(device,"regulator/input-voltage"));
                var output=readings.get(branchId(device,"regulator/output-voltage"));
                var transfer=readings.get(branchId(device,"regulator/input-power"));
                if(input==null || output==null || transfer==null)display.electromagnetism$displayRegulator(0,0,0);
                else {
                    var in=displaySample(input);var out=displaySample(output);var power=displaySample(transfer);
                    if(in!=null && out!=null && power!=null && in.end()==input.windowEnd() && out.end()==in.end() && power.end()==in.end())
                        display.electromagnetism$displayRegulator(in.voltage(),out.voltage(),Math.abs(power.activePower()));
                }
            }
            if(device instanceof com.electromagnetism.content.electromechanics.excitation.ExcitationStatorDevice && infrastructure.level.getBlockEntity(device.pos) instanceof com.electromagnetism.content.electromechanics.excitation.ExcitationStatorBlockEntity field) {
                var coil=readings.get(branchId(device,"main"));if(coil!=null)field.acceptNative(coil);else field.nativeUnavailable();
            }
            if(device instanceof com.george_vi.electroenergetics.content.synchroscope.SynchroscopeDevice && infrastructure.level.getBlockEntity(device.pos) instanceof NativeSynchroscopeDisplay display) {
                var probes=java.util.stream.IntStream.range(0,6).mapToObj(n->readings.get(branchId(device,"sync/"+n))).toList();
                var result=NativeThreePhaseReading.from(probes);display.electromagnetism$synchroscope(result.degrees(),result.correctOrder());
            }
            if(device instanceof com.george_vi.electroenergetics.content.indicator_bulb.IndicatorBulbDevice indicator && infrastructure.level.getBlockEntity(device.pos) instanceof NativeIndicatorDisplay display) {
                var first=readings.get(branchId(device,"main"));var second=readings.get(branchId(device,"indicator/1"));
                boolean valid=(indicator.side==1 || first!=null) && (indicator.side==0 || second!=null);
                display.electromagnetism$indicators(first==null?0:first.rmsVoltage()/70,second==null?0:second.rmsVoltage()/70,valid);
            }
            if((device instanceof com.george_vi.electroenergetics.content.transmission_distribution.transformer.TransformerDevice
                    || device instanceof com.george_vi.electroenergetics.content.transmission_distribution.transformer.TransformerCoreDevice)
                    && infrastructure.level.getBlockEntity(device.pos) instanceof NativeTransformerDisplay display) {
                var primary=readings.get(branchId(device,"primary-meter"));var secondary=readings.get(branchId(device,"secondary-meter"));var voltage=readings.get(branchId(device,"secondary-voltage"));
                if(primary==null || secondary==null || voltage==null)display.electromagnetism$transformerUnavailable();
                else {
                    var in=displaySample(primary);var out=displaySample(voltage);var power=displaySample(secondary);
                    if(in==null || out==null || power==null || in.end()!=primary.windowEnd() || out.end()!=in.end() || power.end()!=in.end())continue;
                    if(device instanceof com.george_vi.electroenergetics.content.transmission_distribution.transformer.TransformerCoreDevice) {
                        var state=infrastructure.level.getBlockState(device.pos);
                        var partnerPos=device.pos.relative(state.getValue(com.george_vi.electroenergetics.content.transmission_distribution.transformer.TransformerCoreBlock.FACING));
                        double dissipation=2*100*NativeTransformerGeometry.coolingPerWinding(infrastructure.level,device.pos);
                        if(display instanceof NativeCoreCooling cooling)cooling.electromagnetism$coolingLimit(dissipation);
                        if(infrastructure.level.getBlockEntity(partnerPos) instanceof NativeTransformerDisplay other) {
                            if(other instanceof NativeCoreCooling cooling)cooling.electromagnetism$coolingLimit(dissipation);
                            other.electromagnetism$acceptTransformer(in.voltage(),out.voltage(),Math.abs(power.activePower()));
                        }
                    }
                    display.electromagnetism$acceptTransformer(in.voltage(),out.voltage(),Math.abs(power.activePower()));
                }
            }
            if(device instanceof NativeEnergyMeter && infrastructure.level.getBlockEntity(device.pos) instanceof NativeEnergyDisplay display) {
                int count=device instanceof com.george_vi.electroenergetics.content.energy_meter.TriPolarEnergyMeterDevice?2:1;
                double watts=0,joules=0;boolean valid=true;
                for(int leg=0;leg<count;leg++) {var reading=readings.get(meterBranchId(device,leg));if(reading==null){valid=false;break;}watts+=reading.activePower();joules+=reading.totalAbsorbedEnergy()-reading.totalReturnedEnergy();}
                if(valid)display.electromagnetism$acceptEnergy(watts,joules);else display.electromagnetism$energyUnavailable();
            }
        }
        for(var device:new HashSet<>(devicesByBranch.values())) {
            if(device instanceof com.george_vi.electroenergetics.content.electric_motor.ElectricMotorDevice
                    && infrastructure.level.getBlockEntity(device.pos) instanceof NativeMotorBoundary motor
                    && SimulationCoordinator.currentMechanicalBranch(live,historical,branchId(device,"main"))) {
                var shaft=readings.get(branchId(device,"main"));var copper=readings.get(branchId(device,"motor/copper"));var flux=readings.get(branchId(device,"motor/flux"));
                if(shaft==null || copper==null || flux==null || shaft.torqueNewtonMetres().isEmpty())motor.electromagnetism$motor(0,0,0,false);
                else motor.electromagnetism$motor(shaft.torqueNewtonMetres().getAsDouble(),shaft.meanVoltage()+copper.meanVoltage()+flux.meanVoltage(),shaft.activePower()+copper.activePower()+flux.activePower(),true);
            }
            if(device instanceof com.george_vi.electroenergetics.content.accumulator.AccumulatorDevice accumulator
                    && infrastructure.level.getBlockEntity(device.pos) instanceof NativeCellDisplay display) {
                double full=ModConfigs.server().nativeCellFullVolts.getF(),capacitance=ModConfigs.server().nativeCellCapacityCoulombs.getF()/full;
                for(int cell=0;cell<(accumulator.isDoubleCell?2:1);cell++) {
                    var observation=readings.get(branchId(device,"cell/"+cell+"/charge"));
                    if(observation==null){display.electromagnetism$cellsUnavailable();break;}
                    display.electromagnetism$cell(cell,observation.meanVoltage()/full,0.5*capacitance*observation.rmsVoltage()*observation.rmsVoltage());
                }
            }
        }
        alternators.apply(infrastructure,historical,live,readings);
        var mechanicalReadings=new HashMap<UUID,Reading>();observations.readings().forEach(r->mechanicalReadings.put(r.branch(),r));
        mechanicalReadings.entrySet().removeIf(e->e.getValue().torqueNewtonMetres().isPresent() && !SimulationCoordinator.currentMechanicalBranch(live,historical,e.getKey()));
        trains.apply(infrastructure,mechanicalReadings,observations.accepted());
        for(var waveform:observations.waveforms()) {
            for(var device:devicesByBranch.values()) {
                if((device instanceof com.electromagnetism.content.instruments.OscilloscopeDevice || device instanceof com.electromagnetism.content.instruments.SignalGeneratorDevice || device instanceof com.electromagnetism.content.instruments.RadioDevice)
                        && ((CeeDeviceIdentity)device).electromagnetism$nativeIdentity().equals(waveform.positive().device())
                        && infrastructure.level.getBlockEntity(device.pos) instanceof CeeWaveformConsumer consumer) {
                    consumer.electromagnetism$publishWaveform(waveform);break;
                }
            }
        }
        for(var event:observations.events()) {
            var eventKey=new EventKey(observations.accepted().revision().session(),event.id());
            if(!confirmedEvents.contains(eventKey)) {
                if(!currentBranches.contains(event.branch())){confirmedEvents.add(eventKey);confirmed.add(event.id());continue;}
                var wire=wiresByBranch.get(event.branch());var device=devicesByBranch.get(event.branch());
                if(historical.recipients().branches().containsKey(event.branch())
                        && (device==null || infrastructure.deviceSD.getDevice(device.pos)!=device)) {
                    confirmedEvents.add(eventKey);confirmed.add(event.id());continue;
                }
                if(event.action()!=EventAction.DAMAGE) {
                    if(!(device instanceof com.george_vi.electroenergetics.content.relay.RelayDevice relay))throw new IllegalStateException("Missing relay event consumer");
                    relay.closed=event.action()==EventAction.ENERGIZE;
                    if(relay.behaviour!=null)relay.behaviour.isClosed=relay.closed^relay.inverted;
                    infrastructure.level.playSound(null,device.pos,net.minecraft.sounds.SoundEvents.LEVER_CLICK,net.minecraft.sounds.SoundSource.BLOCKS,0.1f,relay.closed?1.0f:0.8f);
                    infrastructure.deviceSD.setDirty();
                }
                else if(wire!=null) {
                    var target=historical.recipients().wires().get(event.branch());
                    if(NativeConductors.stillOwns(infrastructure,wire,event.branch(),target.catenary())) {
                        if(target.catenary())infrastructure.removeCatenary(wire.node1().sourcePos(),wire.node2().sourcePos());
                        else infrastructure.removeConnection(wire);
                    }
                }
                else if(panels.owns(event.branch())) {
                    panels.destroy(event.branch());
                }
                else if(device!=null && infrastructure.deviceSD.getDevice(device.pos)==device) {
                    if(device instanceof NativeFuseHolder holder) {
                        holder.electromagnetism$slots().stream().filter(part->event.branch().equals(branchId(device,"fuse-holder/"+part.identity())))
                                .findFirst().ifPresent(slot->holder.electromagnetism$slotBurn(slot.identity()));
                    }
                    else if(device instanceof NativeLamp lamp)lamp.acceptNativeBurn(event);
                    else if(device instanceof HeatingCoilDevice heater)heater.acceptNativeBurn(event);
                    else if(device instanceof com.george_vi.electroenergetics.content.bulb.BulbDevice bulb) {
                        bulb.destroyed=true;
                        var state=infrastructure.level.getBlockState(device.pos);
                        if(state.getBlock() instanceof com.george_vi.electroenergetics.content.bulb.BulbBlock)
                            infrastructure.level.setBlockAndUpdate(device.pos,com.george_vi.electroenergetics.CEEBlocks.BROKEN_BULB.get().withPropertiesOf(state).setValue(com.george_vi.electroenergetics.content.bulb.BulbBlock.LIGHT,0));
                        if(infrastructure.level.getBlockEntity(device.pos) instanceof NativeBulbDisplay display)display.electromagnetism$setNativeLight(0,false);
                    }
                    else if(device instanceof com.george_vi.electroenergetics.content.fuse.FuseDevice fuse) {
                        fuse.isBroken=true;
                        var state=infrastructure.level.getBlockState(device.pos);
                        if(state.getBlock() instanceof com.george_vi.electroenergetics.content.fuse.FuseBlock) {
                            infrastructure.level.setBlockAndUpdate(device.pos,com.george_vi.electroenergetics.CEEBlocks.BROKEN_FUSE.get().withPropertiesOf(state));
                            if(infrastructure.level.getBlockEntity(device.pos) instanceof com.george_vi.electroenergetics.content.fuse.FuseBlockEntity be) {
                                be.setAmperage=fuse.setAmperage;be.setChanged();be.sendData();
                            }
                        }
                    }
                    else if(damageAssemblies.containsKey(device)) {
                        var assembly=damageAssemblies.get(device);
                        // Validate all captured owners before the first world mutation. Neighbour
                        // callbacks may remove the other half, so never remove a replacement.
                        for(var part:assembly)if(infrastructure.deviceSD.getDevice(part.pos)!=part)
                            throw new IllegalStateException("Damage assembly ownership changed at "+part.pos);
                        var states=assembly.stream().map(part->infrastructure.level.getBlockState(part.pos)).toList();
                        for(int member=0;member<assembly.size();member++) {
                            var part=assembly.get(member);
                            if(infrastructure.deviceSD.getDevice(part.pos)==part && infrastructure.level.getBlockState(part.pos).equals(states.get(member)))
                                infrastructure.level.destroyBlock(part.pos,true);
                            if(infrastructure.deviceSD.getDevice(part.pos)==part)infrastructure.deviceSD.removeDevice(part.pos);
                        }
                    }
                    else {infrastructure.level.destroyBlock(device.pos,true);if(infrastructure.deviceSD.getDevice(device.pos)==device)infrastructure.deviceSD.removeDevice(device.pos);}
                }
                confirmedEvents.add(eventKey);readings.remove(event.branch());displayWindows.remove(event.branch());
            }
            confirmed.add(event.id());
        }
        if(!confirmed.isEmpty()) {
            var afterEffects=capture.get();
            if(!afterEffects.blockers().isEmpty())
                throw new IllegalStateException("Cannot verify topology after protection effects: "+afterEffects.blockers());
            var remaining=afterEffects.branches().stream().map(Branch::id).collect(java.util.stream.Collectors.toSet());
            destroyed.accept(beforeEvents.stream().map(Branch::id).filter(id->!remaining.contains(id)).sorted().toList(),afterEffects);
        }
        infrastructure.setDirty();return confirmed;
    }
    private double converterPower(com.george_vi.electroenergetics.content.converter.ConverterDevice converter) {
        var roles=converter.isSource ? List.of("main","output-copper","buffer-gate","fe-buffer")
                : List.of("main","rectifier/1","rectifier/2","rectifier/3","buffer-gate","fe-buffer");
        // CEE uses a positive value for FE-to-electric conversion.
        double watts=Math.max(0,converter.isSource ? -electricalPower(converter,roles) : electricalPower(converter,roles));
        return converter.isSource ? watts : -watts;
    }
    private double electricalPower(SimulatedDevice device,List<String> roles) {
        double watts=0;
        for(String role:roles) {
            var reading=readings.get(branchId(device,role));
            if(reading==null)return 0;
            watts+=reading.activePower();
        }
        return watts;
    }
    void restoreEvents(ListTag events) {
        var restored=new HashSet<EventKey>();
        for(var value:events) {var event=(CompoundTag)value;restored.add(new EventKey(event.getUUID("Session"),event.getUUID("Event")));}
        confirmedEvents.clear();confirmedEvents.addAll(restored);
    }
    void retainPendingEvents(FaultDomainManager domains,Set<UUID> quarantined) {
        if(confirmedEvents.isEmpty())return;
        var pending=domains.pendingEvents();
        confirmedEvents.removeIf(key->!quarantined.contains(key.session()) && !pending.getOrDefault(key.session(),Set.of()).contains(key.event()));
    }
    ListTag saveEvents() {
        var events=new ListTag();
        for(var key:confirmedEvents){var event=new CompoundTag();event.putUUID("Session",key.session());event.putUUID("Event",key.event());events.add(event);}
        return events;
    }
}
