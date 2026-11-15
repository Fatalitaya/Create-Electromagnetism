package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.*;
import com.electromagnetism.config.ModConfigs;
import com.electromagnetism.content.thermal.heater.HeatingCoilDevice;
import com.electromagnetism.content.lighting.fixture.LightFixtureDevice;
import com.electromagnetism.content.lighting.bulb.ILightBulb;
import com.george_vi.electroenergetics.content.creative_battery.CreativeBatteryDevice;
import com.george_vi.electroenergetics.content.electronic_components.resistor.ResistorDevice;
import com.george_vi.electroenergetics.devices.device.SimulatedDevice;
import com.george_vi.electroenergetics.foundation.nodes.*;
import com.george_vi.electroenergetics.simulation.infrastructure.*;
import net.minecraft.nbt.*;
import net.neoforged.fml.ModList;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static com.electromagnetism.simulation.SimulationProtocol.*;

/** Main-thread CEE facts and accepted-result application. No legacy electrical callbacks. */
public final class CeeNativeWorld implements AutoCloseable {
    private static final ExecutorService WORKERS=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64),r->{Thread t=new Thread(r,"electromagnetism-native");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final InfrastructureSavedData infrastructure;
    private JniSimulationBackend backend;
    private SimulationCoordinator coordinator;
    private CompoundTag retained;
    private Diagnostic failure;
    private SimulationCoordinator.Frame latest;
    private final Map<UUID,Reading> readings=new HashMap<>();
    private final Map<UUID,SimulatedDevice> devicesByBranch=new HashMap<>();
    private final Map<UUID,InWorldNodeConnection> wiresByBranch=new HashMap<>();
    private final Map<SimulatedDevice,List<SimulatedDevice>> damageAssemblies=new IdentityHashMap<>();
    private final Set<UUID> protectiveFuses=new HashSet<>();
    private long confirmedEvent;
    private boolean closed;
    private final NativeWorldCommands commands=new NativeWorldCommands();
    private final NativeVoltageProbes probes=new NativeVoltageProbes();
    private final NativePanels panels=new NativePanels();
    private final NativeAlternators alternators=new NativeAlternators();
    private final NativeEnvironment environment=new NativeEnvironment();
    private final NativeTrains trains=new NativeTrains();
    private Set<UUID> catenaryBranches=Set.of();
    private long captureTick=Long.MIN_VALUE;
    private double captureTime;
    private Map<InWorldNode,PortId> probePorts=Map.of();
    public void probe(net.minecraft.server.level.ServerPlayer player,InWorldNode node) {
        capture();
        probes.select(infrastructure,player,node,probePorts);
    }
    public CeeNativeWorld(InfrastructureSavedData infrastructure){this.infrastructure=infrastructure;}
    public boolean exchangeEnergy(UUID branch,double joules,double maximumJoules,boolean commit){
        if(coordinator==null || closed)return false;
        boolean result=coordinator.exchangeEnergy(capture(),branch,joules,maximumJoules,commit);
        if(result && commit){invalidateReadings(false);infrastructure.setDirty();}
        return result;
    }
    public static UUID branchId(SimulatedDevice device,String role) {
        var id=((CeeDeviceIdentity)device).electromagnetism$nativeIdentity();
        return UUID.nameUUIDFromBytes((id.instance()+"/"+id.generation()+"/"+role).getBytes(StandardCharsets.UTF_8));
    }
    public static UUID meterBranchId(SimulatedDevice device,int leg) {
        return branchId(device,"counter/"+((NativeEnergyMeter)device).electromagnetism$counterIdentity()+"/"+leg);
    }
    private void initialize() {
        if(coordinator!=null || failure!=null || closed)return;
        String metadata=ModList.get().getModContainerById("electromagnetism").orElseThrow().getModInfo().getVersion().toString();
        backend=JniSimulationBackend.adaptivePackaged(metadata);
        try {
            Snapshot snapshot=null;
            if(retained!=null) {
                snapshot=new Snapshot(new Revision(retained.getUUID("Session"),retained.getLong("Topology"),retained.getLong("Parameters"),retained.getLong("Sequence")),
                        retained.getString("ModMetadata"),retained.getDouble("AcceptedTime"),retained.getByteArray("State"));
                confirmedEvent=retained.getLong("ConfirmedEvent");
                if(confirmedEvent<0)throw new IllegalArgumentException("Negative event watermark");
            }
            coordinator=new SimulationCoordinator(backend,WORKERS,10000,20_000_000L,snapshot,
                    retained==null?null:retained.getByteArray("InputHistory"));
        } catch(RuntimeException invalid) {failure=new Diagnostic(Status.UNAVAILABLE,"Native world restore failed: "+invalid.getMessage());}
    }
    public void restore(CompoundTag tag) {
        if(coordinator!=null || retained!=null)throw new IllegalStateException("Native world already has an owner");
        retained=tag.copy();
        try {
            if(tag.contains("Environment",Tag.TAG_COMPOUND))environment.restore(tag.getCompound("Environment"));
        } catch(IllegalArgumentException | IllegalStateException invalid) {
            failure=new Diagnostic(Status.UNAVAILABLE,"Native environment restore failed: "+invalid.getMessage());
        }
    }
    public Diagnostic status(){return closed?new Diagnostic(Status.CLOSED,"Native world closed"):failure!=null?failure:coordinator==null?NativeSimulation.UNAVAILABLE:coordinator.status();}
    public Optional<SimulationCoordinator.Progress> progress(){return coordinator==null || closed?Optional.empty():Optional.of(coordinator.progress());}
    public void resumeMissingInputs() {
        if(coordinator==null || closed)throw new IllegalStateException("Native world unavailable");
        coordinator.endTick(capture(),this::apply);
        coordinator.pump(capture());coordinator.resumeMissingInputs();infrastructure.setDirty();
    }
    public Optional<Reading> reading(UUID branch){return Optional.ofNullable(readings.get(branch));}

    public SimulationCoordinator.Frame capture() {
        try{return captureChecked();}
        catch(IllegalArgumentException | IllegalStateException invalid) {
            probePorts=Map.of();
            return new SimulationCoordinator.Frame(List.of(),List.of(),List.of("Invalid world input: "+invalid.getMessage()),RemovalDisposition.REJECT_ENERGIZED);
        }
    }
    private SimulationCoordinator.Frame captureChecked() {
        if(captureTick!=infrastructure.level.getGameTime()){captureTick=infrastructure.level.getGameTime();captureTime=coordinator==null?0:coordinator.progress().targetTime();}
        if(!infrastructure.deviceSD.devicesToAddSable.isEmpty())return new SimulationCoordinator.Frame(List.of(),List.of(),List.of("Pending Sable ownership handoff: "+((NativeSableTransfers)infrastructure.deviceSD).electromagnetism$transferProblem()),RemovalDisposition.REJECT_ENERGIZED);
        var descriptors=new ArrayList<DeviceInput>();var branches=new ArrayList<Branch>();var blockers=new ArrayList<String>();
        panels.begin();
        alternators.begin();
        var waveforms=new ArrayList<WaveformChannel>();
        var ports=new HashMap<InWorldNode,PortId>();var identities=new HashSet<DeviceId>();
        var nodesByPosition=infrastructure.getNodes().stream().collect(java.util.stream.Collectors.groupingBy(InWorldNode::sourcePos));
        devicesByBranch.clear();wiresByBranch.clear();damageAssemblies.clear();protectiveFuses.clear();
        for(SimulatedDevice device:infrastructure.deviceSD.getDevices()) {
            if(!(device instanceof CeeDeviceIdentity identity)) {blockers.add("Missing identity: "+device.type.id()+" at "+device.pos);continue;}
            var id=identity.electromagnetism$nativeIdentity();
            if(!identity.electromagnetism$identityProblem().isEmpty() || !identities.add(id)) {blockers.add("Invalid/duplicate identity: "+device.pos+" "+identity.electromagnetism$identityProblem());continue;}
            if(!infrastructure.level.getChunkSource().hasChunk(device.pos.getX()>>4,device.pos.getZ()>>4)) {blockers.add("Unloaded physical owner: "+device.type.id()+" at "+device.pos);continue;}
            UUID main=branchId(device,"main");
            var p0=new PortId(id,0);var p1=new PortId(id,1);var internal=new PortId(id,2);
            if(device instanceof com.electromagnetism.content.electromechanics.excitation.ExcitationStatorDevice stator) {
                var field=new PortId(id,3);descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1,internal,field),Map.of()));
                branches.add(new Branch(main,p0,internal,"thermal_fuse",thermal(ModConfigs.server().excitationStatorResistance.getF(),100,1,393.15)));
                branches.add(new Branch(branchId(device,"field-flux"),internal,field,"inductor",Map.of("inductanceHenries",1.0)));devicesByBranch.put(main,device);
            } else if(device instanceof com.electromagnetism.content.instruments.RadioDevice || device instanceof com.electromagnetism.content.instruments.AntennaDevice) {
                NativeRadioLinks.declare(device,descriptors,ports);devicesByBranch.put(main,device);continue;
            } else if(device instanceof com.electromagnetism.content.instruments.MicrophoneDevice microphone) {
                descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1),Map.of()));
                branches.add(new Branch(main,p0,p1,"audio_source",com.electromagnetism.content.instruments.AudioNetworking.input(infrastructure.level,microphone,captureTime)));devicesByBranch.put(main,device);
            } else if(device instanceof com.george_vi.electroenergetics.content.electrical_panel.ElectricalPanelDevice panel) {
                panels.capture(panel,id,descriptors,ports,branches);continue;
            } else if(device instanceof com.george_vi.electroenergetics.content.connector.ConnectorDevice
                    || device instanceof com.george_vi.electroenergetics.content.connector.DoubleConnectorDevice
                    || device instanceof com.george_vi.electroenergetics.content.connector.InsulatorDevice
                    || device instanceof com.george_vi.electroenergetics.content.bundled_wire.BundledWireTerminationDevice
                    || device instanceof com.george_vi.electroenergetics.foundation.base.TemporaryDevice) {
                // These are terminal owners, not implicit shorts between distinct node IDs.
                var terminals=nodesByPosition.getOrDefault(device.pos,List.of()).stream()
                        .sorted(Comparator.comparingInt(InWorldNode::id)).toList();
                var declared=new ArrayList<PortId>();
                for(var node:terminals) {var port=new PortId(id,node.id());declared.add(port);ports.put(node,port);}
                descriptors.add(new DeviceInput(id,"ports",declared,Map.of()));
                continue;
            } else if(device instanceof com.george_vi.electroenergetics.content.railway_electrification.pantograph.PantographDevice
                    || device instanceof com.george_vi.electroenergetics.content.railway_electrification.third_rail.RailContactShoeDevice) {
                descriptors.add(new DeviceInput(id,"ports",List.of(p0),Map.of()));ports.put(new InWorldNode(0,device.pos),p0);continue;
            } else if(device instanceof com.george_vi.electroenergetics.content.transmission_distribution.voltage_regulator.VoltageRegulatorDevice regulator) {
                NativeRegulators.capture(infrastructure,regulator,id,descriptors,branches,ports);devicesByBranch.put(main,device);continue;
            } else if(device instanceof com.george_vi.electroenergetics.content.transmission_distribution.hv_switch.HVSwitchDevice hv) {
                descriptors.add(new DeviceInput(id,"ports",List.of(p0),Map.of()));ports.put(new InWorldNode(0,device.pos),p0);
                if(hv.target!=null) {
                    var target=infrastructure.deviceSD.getDevice(hv.target,com.george_vi.electroenergetics.content.connector.ConnectorDevice.class);
                    if(target==null || !target.isHVSwitchTarget)throw new IllegalArgumentException("High voltage switch target missing or invalid");
                    var otherId=((CeeDeviceIdentity)target).electromagnetism$nativeIdentity();
                    if(!Float.isFinite(hv.progress)||hv.progress<0||hv.progress>1)throw new IllegalArgumentException("Invalid high voltage switch position");
                    double gap=Math.max(0.01,(1-hv.progress)*device.pos.getCenter().distanceTo(hv.target.getCenter()));
                    branches.add(new Branch(main,p0,new PortId(otherId,0),"air_gap",NativeAirGap.parameters(hv.progress>=0.9,Math.max(1,50000*(1-hv.progress)),gap)));
                    devicesByBranch.put(main,device);
                }
                continue;
            } else if(device instanceof com.george_vi.electroenergetics.content.relay.RelayDevice relay) {
                var terminals=java.util.stream.IntStream.range(0,4).mapToObj(n->new PortId(id,n)).toList();
                var coil=new PortId(id,4);
                var declared=new ArrayList<>(terminals);declared.add(coil);
                descriptors.add(new DeviceInput(id,"ports",declared,Map.of()));
                for(int n=0;n<4;n++)ports.put(new InWorldNode(n,device.pos),terminals.get(n));
                branches.add(new Branch(main,coil,p1,"relay",Map.of("coilOhms",1000.0,"closedOhms",0.001,"openOhms",1e12,"pickupVolts",4.0,"dropoutVolts",3.0,"inverted",relay.inverted?1.0:0.0,"initialActive",0.0),List.of(terminals.get(2),terminals.get(3))));
                branches.add(new Branch(branchId(device,"relay/flux"),p0,coil,"inductor",Map.of("inductanceHenries",0.05)));
                devicesByBranch.put(main,device);continue;
            } else if(device instanceof com.george_vi.electroenergetics.content.rotor.AlternatorBrushesDevice || device instanceof com.george_vi.electroenergetics.content.rotor.ThreePhaseAlternatorBrushesDevice) {
                alternators.capture(infrastructure,device,id,descriptors,branches,ports,identities,devicesByBranch);continue;
            } else if(device instanceof com.george_vi.electroenergetics.content.electric_motor.ElectricMotorDevice) {
                if(!(infrastructure.level.getBlockEntity(device.pos) instanceof NativeMotorBoundary motor))throw new IllegalArgumentException("Missing motor shaft boundary");
                var winding=new PortId(id,3);
                descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1,internal,winding),Map.of()));
                branches.add(new Branch(main,internal,p1,"shaft_boundary",Map.of("newtonMetresPerAmpere",(double)ModConfigs.server().nativeMotorTorqueConstant.getF(),"radiansPerSecond",motor.electromagnetism$shaftSpeedRpm()*Math.PI/30)));
                branches.add(new Branch(branchId(device,"motor/copper"),p0,winding,"thermal_fuse",thermal(ModConfigs.server().nativeMotorWindingOhms.getF(),100,1,393.15)));
                branches.add(new Branch(branchId(device,"motor/flux"),winding,internal,"inductor",Map.of("inductanceHenries",(double)ModConfigs.server().nativeMotorWindingHenries.getF())));
                devicesByBranch.put(branchId(device,"motor/copper"),device);devicesByBranch.put(main,device);
            } else if(device instanceof com.george_vi.electroenergetics.content.electric_pump.ElectricPumpDevice) {
                if(!(infrastructure.level.getBlockEntity(device.pos) instanceof NativePumpBoundary pump))throw new IllegalArgumentException("Missing pump boundary");
                if(!pump.electromagnetism$pump().problem().isEmpty())throw new IllegalArgumentException(pump.electromagnetism$pump().problem());
                var returnPort=new PortId(id,3);var rectifier=new PortId(id,4);
                descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1,internal,returnPort,rectifier),Map.of()));
                branches.add(new Branch(branchId(device,"pump-buffer"),internal,returnPort,"capacitor",Map.of("capacitanceFarads",2*10000.0/(48*48))));
                branches.add(new Branch(branchId(device,"pump/limit"),rectifier,internal,"battery_contact",Map.of("resistanceOhms",1.0,"fullVolts",48.0,"taperVolts",2.4),List.of(returnPort)));
                Map<String,Double> diode=Map.of("saturationAmperes",1e-9,"thermalVolts",0.05,"junctionFarads",1e-10);
                branches.add(new Branch(main,p0,rectifier,"diode",diode));branches.add(new Branch(branchId(device,"pump/rectifier/1"),p1,rectifier,"diode",diode));
                branches.add(new Branch(branchId(device,"pump/rectifier/2"),returnPort,p0,"diode",diode));branches.add(new Branch(branchId(device,"pump/rectifier/3"),returnPort,p1,"diode",diode));
                devicesByBranch.put(main,device);
            } else if(device instanceof com.george_vi.electroenergetics.content.converter.ConverterDevice converter) {
                if(!(infrastructure.level.getBlockEntity(device.pos) instanceof NativeConverterBoundary boundary))throw new IllegalArgumentException("Missing converter external boundary");
                if(!boundary.electromagnetism$converter().problem().isEmpty())throw new IllegalArgumentException(boundary.electromagnetism$converter().problem());
                var buffer=new PortId(id,2);var bufferReturn=new PortId(id,3);var output=new PortId(id,4);var gate=new PortId(id,5);
                double capacity=ModConfigs.server().nativeConverterBufferJoules.getF();
                descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1,buffer,bufferReturn,output,gate),Map.of()));
                branches.add(new Branch(branchId(device,"fe-buffer"),buffer,bufferReturn,"capacitor",Map.of("capacitanceFarads",2*capacity/(48*48))));
                branches.add(new Branch(branchId(device,"buffer-gate"),gate,buffer,"battery_contact",Map.of("resistanceOhms",0.01,"fullVolts",48.0,"taperVolts",2.4),List.of(bufferReturn)));
                if(converter.isSource) {
                    if(!Double.isFinite(converter.voltage)||converter.voltage<0)throw new IllegalArgumentException("Invalid converter output voltage");
                    branches.add(new Branch(main,output,p1,"regulated_converter",Map.of("outputVolts",converter.voltage,"kneeVolts",24.0,"efficiency",(double)ModConfigs.server().nativeConverterEfficiency.getF()),List.of(gate,bufferReturn)));
                    branches.add(new Branch(branchId(device,"output-copper"),output,p0,"resistor",Map.of("resistanceOhms",0.1)));
                } else {
                    // A full bridge charges a finite DC link from either input polarity.
                    Map<String,Double> diode=Map.of("saturationAmperes",1e-9,"thermalVolts",0.05,"junctionFarads",1e-10);
                    branches.add(new Branch(main,p0,gate,"diode",diode));
                    branches.add(new Branch(branchId(device,"rectifier/1"),p1,gate,"diode",diode));
                    branches.add(new Branch(branchId(device,"rectifier/2"),bufferReturn,p0,"diode",diode));
                    branches.add(new Branch(branchId(device,"rectifier/3"),bufferReturn,p1,"diode",diode));
                }
                devicesByBranch.put(main,device);
            } else if(device instanceof com.george_vi.electroenergetics.content.accumulator.AccumulatorDevice accumulator) {
                int count=accumulator.isDoubleCell?2:1;
                var declared=new ArrayList<PortId>();
                double full=ModConfigs.server().nativeCellFullVolts.getF();
                for(int cell=0;cell<count;cell++) {
                    var positive=new PortId(id,cell*2);var negative=new PortId(id,cell*2+1);
                    var copper=new PortId(id,4+cell*3);var chemical=new PortId(id,5+cell*3);var polarization=new PortId(id,6+cell*3);
                    declared.addAll(List.of(positive,negative,copper,chemical,polarization));
                    ports.put(new InWorldNode(cell*2,device.pos),positive);ports.put(new InWorldNode(cell*2+1,device.pos),negative);
                    UUID loss=branchId(device,"cell/"+cell+"/copper");
                    branches.add(new Branch(loss,positive,copper,"thermal_resistor",Map.of("ambientOhms",(double)ModConfigs.server().nativeCellResistanceOhms.getF(),"coefficientPerKelvin",0.0039,"heatCapacityJoulesPerKelvin",100.0,"conductanceWattsPerKelvin",1.0,"ambientKelvin",293.15)));
                    branches.add(new Branch(branchId(device,"cell/"+cell+"/gate"),polarization,chemical,"battery_contact",Map.of("resistanceOhms",0.001,"fullVolts",full,"taperVolts",full*0.05),List.of(negative)));
                    branches.add(new Branch(branchId(device,"cell/"+cell+"/polarization-r"),copper,polarization,"resistor",Map.of("resistanceOhms",0.05)));
                    branches.add(new Branch(branchId(device,"cell/"+cell+"/polarization-c"),copper,polarization,"capacitor",Map.of("capacitanceFarads",0.2)));
                    branches.add(new Branch(branchId(device,"cell/"+cell+"/charge"),chemical,negative,"capacitor",Map.of("capacitanceFarads",ModConfigs.server().nativeCellCapacityCoulombs.getF()/full)));
                    devicesByBranch.put(loss,device);
                }
                descriptors.add(new DeviceInput(id,"ports",declared,Map.of()));continue;
            } else if(device instanceof com.george_vi.electroenergetics.content.pole.ConcretePoleDevice) {
                var terminals=java.util.stream.IntStream.range(0,4).mapToObj(n->new PortId(id,n)).toList();
                descriptors.add(new DeviceInput(id,"ports",terminals,Map.of()));
                var state=infrastructure.level.getBlockState(device.pos);
                if(!(state.getBlock() instanceof com.george_vi.electroenergetics.content.pole.ConcretePoleBlock))throw new IllegalArgumentException("Concrete pole world owner missing at "+device.pos);
                var upper=infrastructure.deviceSD.getDevice(device.pos.above());
                boolean bottom=!(infrastructure.deviceSD.getDevice(device.pos.below()) instanceof com.george_vi.electroenergetics.content.pole.ConcretePoleDevice);
                boolean top=!(upper instanceof com.george_vi.electroenergetics.content.pole.ConcretePoleDevice);
                var config=ModConfigs.server();
                var conductor=thermal(config.nativePoleConductorOhmsPerMetre.getF(),config.nativeWireHeatCapacityJoulesPerKelvinMetre.getF(),config.nativeWireCoolingWattsPerKelvinMetre.getF(),config.nativeWireTripKelvin.getF());
                if(bottom || top)for(int pin=0;pin<4;pin++) {
                    ports.put(new InWorldNode(pin,device.pos),terminals.get(pin));
                    UUID edge=branchId(device,"terminal-ring/"+pin);devicesByBranch.put(edge,device);
                    branches.add(new Branch(edge,terminals.get(pin),terminals.get((pin+1)%4),"thermal_fuse",conductor));
                }
                if(!top) {
                    var upperId=((CeeDeviceIdentity)upper).electromagnetism$nativeIdentity();
                    UUID edge=branchId(device,"pole-segment/"+upperId.instance()+"/"+upperId.generation());devicesByBranch.put(edge,device);
                    branches.add(new Branch(edge,p0,new PortId(upperId,0),"thermal_fuse",conductor));
                }
            } else if(device instanceof com.electromagnetism.content.acoustics.bell.ElectricBellDevice) {
                descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1,internal),Map.of()));
                // Fixed 20-ohm coil, 50-mH magnetic storage; mechanical ringing is
                // an accepted-power display envelope, with no microphone input.
                branches.add(new Branch(main,p0,internal,"thermal_fuse",thermal(20,20,0.2,393.15)));
                UUID magnetic=branchId(device,"coil-flux");devicesByBranch.put(magnetic,device);
                branches.add(new Branch(magnetic,internal,p1,"inductor",Map.of("inductanceHenries",0.05)));
            } else if(device instanceof com.electromagnetism.content.instruments.OscilloscopeDevice) {
                descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1),Map.of()));
                branches.add(new Branch(main,p0,p1,"resistor",Map.of("resistanceOhms",1e6)));
                waveforms.add(new WaveformChannel(branchId(device,"scope"),p0,p1));
            } else if(device instanceof com.electromagnetism.content.instruments.SignalGeneratorDevice) {
                if(!(infrastructure.level.getBlockEntity(device.pos) instanceof com.electromagnetism.content.instruments.SignalGeneratorBlockEntity generator))throw new IllegalStateException("Missing signal generator settings at "+device.pos);
                var settings=generator.settings();
                boolean ac=settings.enabled() && settings.wave()!=com.electromagnetism.core.signal.SignalGenerator.Wave.DC;
                int harmonics=ac && settings.wave()!=com.electromagnetism.core.signal.SignalGenerator.Wave.SINE?8:1;
                var declared=new ArrayList<PortId>(List.of(p0,p1));
                for(int j=0;j<harmonics;j++)declared.add(new PortId(id,j+2));
                descriptors.add(new DeviceInput(id,"ports",declared,Map.of()));
                for(int j=0;j<harmonics;j++) {
                    var output=declared.get(j+2);var input=j==harmonics-1?p1:declared.get(j+3);
                    int harmonic=2*j+1;
                    if(ac) {
                        double weight=switch(settings.wave()) {
                            case SINE -> 1.0;
                            case SQUARE -> 4.0/(Math.PI*harmonic)*(1.0-harmonic/16.0)/0.9603637867004527;
                            case TRIANGLE -> 8.0/(Math.PI*Math.PI*harmonic*harmonic)/0.974702508054903;
                            case DC -> throw new IllegalStateException("DC in periodic source");
                        };
                        // Finite Fourier synthesis: square uses Fejer taper, triangle
                        // odd harmonics, normalized to unit peak at pi/2. Rust owns every harmonic's accepted phase.
                        double phase=settings.wave()==com.electromagnetism.core.signal.SignalGenerator.Wave.TRIANGLE && j%2==1?Math.PI:0;
                        branches.add(new Branch(branchId(device,"generator/"+settings.wave()+"/"+harmonic),output,input,"sine_voltage_source",Map.of("offsetVolts",j==0?settings.offset():0.0,"peakVolts",settings.amplitude()*weight,"frequencyHz",settings.frequency()*harmonic,"phaseRadians",phase)));
                    } else branches.add(new Branch(branchId(device,"generator/dc"),output,input,"dc_voltage_source",Map.of("voltageVolts",settings.enabled()?settings.offset()+settings.amplitude():0.0)));
                }
                branches.add(new Branch(main,p0,declared.get(2),"resistor",Map.of("resistanceOhms",50.0)));
                waveforms.add(new WaveformChannel(branchId(device,"scope"),p0,p1));
            } else if(device instanceof com.george_vi.electroenergetics.content.ground_rod.GroundRodDevice) {
                // One conducting earth node per dimension; finite 1 S rod contact.
                var earthId=new DeviceId(UUID.nameUUIDFromBytes(("electromagnetism/earth/"+infrastructure.level.dimension().location()).getBytes(StandardCharsets.UTF_8)),0);
                var earth=new PortId(earthId,0);
                if(identities.add(earthId))descriptors.add(new DeviceInput(earthId,"ports",List.of(earth),Map.of()));
                descriptors.add(new DeviceInput(id,"ports",List.of(p0),Map.of()));
                branches.add(new Branch(main,p0,earth,"resistor",Map.of("resistanceOhms",1.0)));
                ports.put(new InWorldNode(0,device.pos),p0);devicesByBranch.put(main,device);continue;
            } else if(device instanceof com.george_vi.electroenergetics.content.electronic_components.diode.DiodeDevice) {
                descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1),Map.of()));
                // Actual CEE node labels: 0 cathode, 1 anode. Fixed SI junction parameters.
                branches.add(new Branch(main,p1,p0,"diode",Map.of("saturationAmperes",1e-9,"thermalVolts",0.05,"junctionFarads",1e-11)));
            } else if(device instanceof com.electromagnetism.content.electrical.switching.ReversingSwitchDevice reversing) {
                var p3=new PortId(id,3);
                descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1,internal,p3),Map.of()));
                boolean reverse=infrastructure.level.getBlockState(device.pos).getValue(com.george_vi.electroenergetics.content.cut_off_switch.CutOffSwitchBlock.CLOSED);
                double resistance=ModConfigs.server().reversingSwitchResistance.getF();
                branches.add(new Branch(branchId(device,"pole/0"),p0,reverse?p3:internal,"resistor",Map.of("resistanceOhms",resistance)));
                branches.add(new Branch(branchId(device,"pole/1"),p1,reverse?internal:p3,"resistor",Map.of("resistanceOhms",resistance)));
                ports.put(new InWorldNode(2,device.pos),internal);ports.put(new InWorldNode(3,device.pos),p3);
            } else if(device instanceof CreativeBatteryDevice source) {
                descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1,internal),Map.of()));
                if(source.acFrequency==0) branches.add(new Branch(branchId(device,"source/dc"),internal,p1,"dc_voltage_source",Map.of("voltageVolts",source.voltage)));
                else {
                    double phase=Math.toRadians(source.phaseOffset)+Math.PI/2+(source.voltage<0?Math.PI:0);
                    // Changing configured phase replaces the ideal source at the accepted boundary.
                    // Frequency is deliberately absent from its identity: edits preserve accepted phase.
                    branches.add(new Branch(branchId(device,"source/ac/"+Double.toHexString(phase)),internal,p1,"sine_voltage_source",Map.of("offsetVolts",0.0,"peakVolts",Math.abs(source.voltage),
                            "frequencyHz",source.acFrequency,"phaseRadians",phase)));
                }
                branches.add(new Branch(branchId(device,"main"),p0,internal,"resistor",Map.of("resistanceOhms",0.001)));
            } else if(device instanceof com.electromagnetism.content.electronics.bjt.TransistorDevice transistor) {
                var lead=new PortId(id,3);
                descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1,internal,lead),Map.of()));
                branches.add(new Branch(main,lead,internal,transistor.pnp?"pnp_bipolar":"npn_bipolar",
                        SemiconductorParameters.bipolar(transistor.beta,transistor.vbeOn),List.of(p0)));
                UUID protection=branchId(device,"output_protection");
                branches.add(new Branch(protection,p1,lead,"thermal_fuse",SemiconductorParameters.outputProtection(transistor.icMax)));
                devicesByBranch.put(protection,device);ports.put(new InWorldNode(2,device.pos),internal);
            } else if(device instanceof com.electromagnetism.content.electronics.mosfet.MosfetDevice mosfet) {
                var lead=new PortId(id,3);
                descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1,internal,lead),Map.of()));
                branches.add(new Branch(main,lead,internal,mosfet.pChannel?"p_mosfet":"n_mosfet",
                        SemiconductorParameters.mosfet(mosfet.vth,mosfet.rdsOn),List.of(p0)));
                UUID protection=branchId(device,"output_protection");
                branches.add(new Branch(protection,p1,lead,"thermal_fuse",SemiconductorParameters.outputProtection(mosfet.idMax)));
                devicesByBranch.put(protection,device);ports.put(new InWorldNode(2,device.pos),internal);
            } else if(device instanceof com.george_vi.electroenergetics.content.transmission_distribution.hv_capacitor.HVCapacitorDevice capacitor) {
                descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1,internal),Map.of()));
                var positive=p0;var negative=p1;
                if(capacitor.facing!=null) {
                    var connector=infrastructure.deviceSD.getDevice(device.pos.relative(capacitor.facing),com.george_vi.electroenergetics.content.connector.DoubleConnectorDevice.class);
                    if(connector!=null) {
                        var connectorId=((CeeDeviceIdentity)connector).electromagnetism$nativeIdentity();
                        positive=new PortId(connectorId,0);negative=new PortId(connectorId,1);
                    }
                }
                branches.add(new Branch(main,positive,internal,"capacitor",Map.of("capacitanceFarads",capacitor.capacitance)));
                storageParasitics(device,positive,negative,internal,capacitor.maxVoltage.getAsDouble(),branches);
            } else if(device instanceof com.george_vi.electroenergetics.content.electronic_components.capacitor.CapacitorDevice capacitor) {
                descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1,internal),Map.of()));
                branches.add(new Branch(main,p0,internal,"capacitor",Map.of("capacitanceFarads",capacitor.capacitance)));
                storageParasitics(device,p0,p1,internal,capacitor.maxVoltage.getAsDouble(),branches);
            } else if(device instanceof com.george_vi.electroenergetics.content.electronic_components.inductor.InductorDevice inductor) {
                descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1,internal),Map.of()));
                branches.add(new Branch(main,p0,internal,"inductor",Map.of("inductanceHenries",inductor.inductance)));
                storageParasitics(device,p0,p1,internal,inductor.maxVoltage.getAsDouble(),branches);
            } else if(device instanceof com.george_vi.electroenergetics.content.buzzer.BuzzerDevice) {
                descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1),Map.of()));
                branches.add(new Branch(main,p0,p1,"thermal_fuse",thermal(1000,2,0.1,393.15)));
            } else if(device instanceof com.george_vi.electroenergetics.content.indicator_bulb.IndicatorBulbDevice indicator) {
                if(indicator.side<0 || indicator.side>2)throw new IllegalArgumentException("Invalid indicator side at "+device.pos);
                var terminals=java.util.stream.IntStream.range(0,4).mapToObj(n->new PortId(id,n)).toList();
                descriptors.add(new DeviceInput(id,"ports",terminals,Map.of()));for(int n=0;n<4;n++)ports.put(new InWorldNode(n,device.pos),terminals.get(n));
                for(int side=0;side<2;side++)if(indicator.side==2 || indicator.side==side) {
                    UUID branch=side==0?main:branchId(device,"indicator/1");devicesByBranch.put(branch,device);
                    branches.add(new Branch(branch,terminals.get(2*side),terminals.get(2*side+1),"thermal_fuse",NativeProtection.indicator()));
                }
            } else if(device instanceof com.george_vi.electroenergetics.content.bulb.BulbDevice bulb) {
                descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1),Map.of()));
                double resistance=com.george_vi.electroenergetics.config.CEEConfigs.server().resistanceValues.bulbResistance.get();
                if(!bulb.destroyed)branches.add(new Branch(main,p0,p1,"thermal_fuse",thermal(resistance,2,285*285/resistance/300,693.15)));
            } else if(device instanceof com.george_vi.electroenergetics.content.resistive_heater.ResistiveHeaterDevice) {
                descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1),Map.of()));
                double resistance=com.george_vi.electroenergetics.config.CEEConfigs.server().resistanceValues.resistiveHeaterResistance.get();
                branches.add(new Branch(main,p0,p1,"thermal_fuse",thermal(resistance,200,10,1093.15)));
            } else if(device instanceof com.george_vi.electroenergetics.content.potentiometer.PotentiometerDevice potentiometer) {
                if(!Double.isFinite(potentiometer.resistance) || potentiometer.resistance<=0 || !Float.isFinite(potentiometer.progress) || potentiometer.progress<0 || potentiometer.progress>1)
                    throw new IllegalArgumentException("Potentiometer requires positive resistance and wiper position in [0,1]");
                descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1,internal),Map.of()));ports.put(new InWorldNode(2,device.pos),internal);
                // Each track half has finite 1-mOhm contact resistance and its
                // own thermal node; changing the wiper preserves accepted heat.
                var config=ModConfigs.server();
                branches.add(new Branch(main,p0,p1,"thermal_fuse",thermal(0.001+potentiometer.progress*potentiometer.resistance,config.nativeResistorHeatCapacityJoulesPerKelvin.getF()/2,config.nativeResistorCoolingWattsPerKelvin.getF()/2,config.nativeResistorTripKelvin.getF())));
                UUID second=branchId(device,"track/1");devicesByBranch.put(second,device);
                branches.add(new Branch(second,p1,internal,"thermal_fuse",thermal(0.001+(1-potentiometer.progress)*potentiometer.resistance,config.nativeResistorHeatCapacityJoulesPerKelvin.getF()/2,config.nativeResistorCoolingWattsPerKelvin.getF()/2,config.nativeResistorTripKelvin.getF())));
            } else if(device instanceof com.george_vi.electroenergetics.content.transmission_distribution.sf6_breaker.SF6BreakerDevice breaker) {
                descriptors.add(new DeviceInput(id,"ports",List.of(p0),Map.of()));
                ports.put(new InWorldNode(0,device.pos),p0);
                var state=infrastructure.level.getBlockState(device.pos);
                if(!(state.getBlock() instanceof com.george_vi.electroenergetics.content.transmission_distribution.sf6_breaker.SF6BreakerBlock)
                        || state.getValue(com.george_vi.electroenergetics.content.transmission_distribution.sf6_breaker.SF6BreakerBlock.BASE)!=breaker.base)
                    throw new IllegalArgumentException("SF6 breaker world/base ownership mismatch at "+device.pos);
                var otherPos=breaker.base?device.pos.above():device.pos.below();
                if(!(infrastructure.deviceSD.getDevice(otherPos) instanceof com.george_vi.electroenergetics.content.transmission_distribution.sf6_breaker.SF6BreakerDevice other) || other.base==breaker.base)
                    throw new IllegalArgumentException("SF6 breaker requires its complementary half at "+otherPos);
                var otherState=infrastructure.level.getBlockState(otherPos);
                if(otherState.getBlock()!=state.getBlock()
                        || otherState.getValue(com.george_vi.electroenergetics.content.transmission_distribution.sf6_breaker.SF6BreakerBlock.BASE)!=other.base)
                    throw new IllegalArgumentException("SF6 complementary world owner mismatch at "+otherPos);
                damageAssemblies.put(device,List.of(device,other));
                if(breaker.base) {
                    boolean closed=!breaker.powered && !other.powered;
                    // Insulator redstone is a game command boundary. The native model
                    // owns contact current, thermal state and destructive interruption.
                    for(int depth=1;depth<=2;depth++) {
                        if(!(infrastructure.deviceSD.getDevice(device.pos.below(depth)) instanceof com.george_vi.electroenergetics.content.connector.InsulatorDevice insulator))break;
                        closed &= !insulator.powered;
                    }
                    var otherId=((CeeDeviceIdentity)other).electromagnetism$nativeIdentity();
                    double rating=ModConfigs.server().nativeSwitchRatedAmperes.getF(),cooling=rating*rating*0.01/100;
                    branches.add(new Branch(main,p0,new PortId(otherId,0),"thermal_fuse",thermal(closed?0.01:1e12,cooling,cooling,393.15)));
                    devicesByBranch.put(main,device);
                }
                continue;
            } else if(device instanceof com.george_vi.electroenergetics.content.cut_off_switch.CutOffSwitchDevice contact) {
                int lines=contact.type==com.george_vi.electroenergetics.CEESimulatedDevices.DOUBLE_SWITCH.get()?2:1;
                var terminals=java.util.stream.IntStream.range(0,lines*2).mapToObj(n->new PortId(id,n)).toList();
                descriptors.add(new DeviceInput(id,"ports",terminals,Map.of()));
                for(int n=0;n<terminals.size();n++)ports.put(new InWorldNode(n,device.pos),terminals.get(n));
                for(int line=0;line<lines;line++) {
                    UUID branch=line==0?main:branchId(device,"contact/"+line);devicesByBranch.put(branch,device);
                    branches.add(new Branch(branch,terminals.get(line),terminals.get(lines+line),"thermal_fuse",switchContact(contact.isClosed)));
                }
            } else if(device instanceof com.george_vi.electroenergetics.content.cut_off_switch.MomentarySwitchDevice contact) {
                if(contact.closedTicks < -1 || contact.closedTicks > 5)throw new IllegalArgumentException("Momentary switch hold must be -1..5 game ticks at "+device.pos);
                descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1),Map.of()));
                boolean pressed=infrastructure.level.getBlockState(device.pos).getValue(com.george_vi.electroenergetics.content.cut_off_switch.MomentarySwitchBlock.CLOSED);
                branches.add(new Branch(main,p0,p1,"thermal_fuse",switchContact(pressed)));
            } else if(device instanceof com.george_vi.electroenergetics.content.redstone_relay.RedstoneRelayDevice) {
                var state=infrastructure.level.getBlockState(device.pos);
                if(!com.george_vi.electroenergetics.CEEBlocks.REDSTONE_RELAY.has(state))throw new IllegalArgumentException("Missing redstone relay world state at "+device.pos);
                boolean closed=state.getValue(com.george_vi.electroenergetics.content.redstone_relay.RedstoneRelayBlock.POWERED)^state.getValue(com.george_vi.electroenergetics.content.redstone_relay.RedstoneRelayBlock.INVERTED);
                descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1),Map.of()));
                branches.add(new Branch(main,p0,p1,"thermal_fuse",switchContact(closed)));
            } else if(device instanceof com.george_vi.electroenergetics.content.transmission_distribution.current_transformer.CurrentTransformerDevice transformer) {
                var terminals=java.util.stream.IntStream.range(0,6).mapToObj(n->new PortId(id,n)).toList();
                descriptors.add(new DeviceInput(id,"ports",terminals,Map.of()));
                for(int n=0;n<4;n++)ports.put(new InWorldNode(n,device.pos),terminals.get(n));
                if(!(infrastructure.level.getBlockState(device.pos).getBlock() instanceof com.george_vi.electroenergetics.content.transmission_distribution.current_transformer.CurrentTransformerBlock))
                    throw new IllegalArgumentException("Current transformer owner has no matching world block at "+device.pos);
                if(!(infrastructure.deviceSD.getDevice(device.pos.below()) instanceof com.george_vi.electroenergetics.content.transmission_distribution.current_transformer.CurrentTransformerDevice)) {
                    var top=transformer;
                    var assembly=new ArrayList<SimulatedDevice>();assembly.add(device);
                    for(int y=device.pos.getY()+1;y<infrastructure.level.getMaxBuildHeight();y++) {
                        var next=infrastructure.deviceSD.getDevice(new net.minecraft.core.BlockPos(device.pos.getX(),y,device.pos.getZ()));
                        if(!(next instanceof com.george_vi.electroenergetics.content.transmission_distribution.current_transformer.CurrentTransformerDevice part))break;
                        if(!(infrastructure.level.getBlockState(part.pos).getBlock() instanceof com.george_vi.electroenergetics.content.transmission_distribution.current_transformer.CurrentTransformerBlock))
                            throw new IllegalArgumentException("Current transformer member world owner missing at "+part.pos);
                        assembly.add(part);top=part;
                    }
                    damageAssemblies.put(device,List.copyOf(assembly));
                    double ratio=top.ratio;
                    if(!Double.isFinite(ratio) || ratio<=0)throw new IllegalArgumentException("Current transformer requires positive Np/Ns turns ratio at "+top.pos);
                    var topId=((CeeDeviceIdentity)top).electromagnetism$nativeIdentity();
                    var primaryDot=new PortId(topId,0);var primaryReturn=new PortId(topId,1);
                    var config=ModConfigs.server();double lm=config.nativeTransformerMagnetizingHenries.getF(),leak=config.nativeTransformerLeakageHenries.getF();
                    branches.add(new Branch(main,terminals.get(4),primaryReturn,"coupled_windings",Map.of("primaryHenries",lm+leak,"secondaryHenries",lm/(ratio*ratio)+leak,"mutualHenries",lm/ratio,"primaryOhms",0.0,"secondaryOhms",0.0),List.of(terminals.get(5),terminals.get(3))));
                    var copper=thermal(config.nativeTransformerWindingOhms.getF(),config.nativeTransformerHeatCapacityJoulesPerKelvin.getF(),config.nativeTransformerCoolingWattsPerKelvin.getF(),393.15);
                    UUID primaryHeat=branchId(device,"winding-heat/0"),secondaryHeat=branchId(device,"winding-heat/1");
                    devicesByBranch.put(primaryHeat,device);devicesByBranch.put(secondaryHeat,device);
                    branches.add(new Branch(primaryHeat,primaryDot,terminals.get(4),"thermal_fuse",copper));
                    branches.add(new Branch(secondaryHeat,terminals.get(5),terminals.get(2),"thermal_fuse",copper));
                }
            } else if(device instanceof com.george_vi.electroenergetics.content.variac.VariacDevice variac) {
                double gain=variac.ratio*variac.progress;
                if(!Double.isFinite(variac.ratio) || variac.ratio<=0 || !Float.isFinite(variac.progress) || variac.progress<0 || variac.progress>1 || !Double.isFinite(gain))
                    throw new IllegalArgumentException("Variac requires positive turns gain and tap position in [0,1] at "+device.pos);
                var terminals=java.util.stream.IntStream.range(0,5).mapToObj(n->new PortId(id,n)).toList();
                descriptors.add(new DeviceInput(id,"ports",terminals,Map.of()));
                for(int n=0;n<3;n++)ports.put(new InWorldNode(n,device.pos),terminals.get(n));
                var config=ModConfigs.server();double lm=config.nativeTransformerMagnetizingHenries.getF(),leak=config.nativeTransformerLeakageHenries.getF();
                // Three-terminal, linear magnetic model. The finite leakage branch remains
                // at zero tap; changing the tap preserves flux and books external work in Rust.
                branches.add(new Branch(main,terminals.get(3),p1,"coupled_windings",Map.of("primaryHenries",lm+leak,"secondaryHenries",lm*gain*gain+leak,"mutualHenries",lm*gain,"primaryOhms",0.0,"secondaryOhms",0.0),List.of(terminals.get(4),p1)));
                var copper=thermal(config.nativeTransformerWindingOhms.getF(),config.nativeTransformerHeatCapacityJoulesPerKelvin.getF(),config.nativeTransformerCoolingWattsPerKelvin.getF()*(variac.oilLogged?2:1),393.15);
                for(int winding=0;winding<2;winding++) {
                    UUID heat=branchId(device,"winding-heat/"+winding);devicesByBranch.put(heat,device);
                    branches.add(new Branch(heat,terminals.get(winding==0?0:4),terminals.get(winding==0?3:2),"thermal_fuse",copper));
                }
            } else if(device instanceof com.george_vi.electroenergetics.content.transmission_distribution.transformer.TransformerDevice
                    || device instanceof com.george_vi.electroenergetics.content.transmission_distribution.transformer.TransformerCoreDevice) {
                var terminals=new ArrayList<>(java.util.stream.IntStream.range(0,8).mapToObj(n->new PortId(id,n)).toList());
                descriptors.add(new DeviceInput(id,"ports",terminals,Map.of()));
                boolean core=device instanceof com.george_vi.electroenergetics.content.transmission_distribution.transformer.TransformerCoreDevice;
                for(int n=0;n<(core?2:4);n++)ports.put(new InWorldNode(n,device.pos),terminals.get(n));
                double ratio;
                if(core) {
                    var state=infrastructure.level.getBlockState(device.pos);
                    if(!(state.getBlock() instanceof com.george_vi.electroenergetics.content.transmission_distribution.transformer.TransformerCoreBlock))throw new IllegalArgumentException("Transformer core world owner missing at "+device.pos);
                    var facing=state.getValue(com.george_vi.electroenergetics.content.transmission_distribution.transformer.TransformerCoreBlock.FACING);
                    var partner=infrastructure.deviceSD.getDevice(device.pos.relative(facing));
                    var other=infrastructure.level.getBlockState(device.pos.relative(facing));
                    if(!(partner instanceof com.george_vi.electroenergetics.content.transmission_distribution.transformer.TransformerCoreDevice)
                            || other.getBlock()!=state.getBlock() || other.getValue(com.george_vi.electroenergetics.content.transmission_distribution.transformer.TransformerCoreBlock.FACING)!=facing.getOpposite())
                        throw new IllegalArgumentException("Incomplete transformer core pair at "+device.pos);
                    if(facing.getAxisDirection()==net.minecraft.core.Direction.AxisDirection.NEGATIVE)continue;
                    damageAssemblies.put(device,List.of(device,partner));
                    var partnerId=((CeeDeviceIdentity)partner).electromagnetism$nativeIdentity();
                    terminals.set(2,new PortId(partnerId,0));terminals.set(3,new PortId(partnerId,1));
                    // Turns are the explicit user input; the upstream zero sentinel means 1:1.
                    ratio=((com.george_vi.electroenergetics.content.transmission_distribution.transformer.TransformerCoreDevice)device).ratio;
                    if(ratio==0)ratio=1;
                } else ratio=((com.george_vi.electroenergetics.content.transmission_distribution.transformer.TransformerDevice)device).ratio;
                if(!Double.isFinite(ratio) || ratio<=0)throw new IllegalArgumentException("Transformer requires positive Np/Ns turns ratio at "+device.pos);
                var config=ModConfigs.server();double lm=config.nativeTransformerMagnetizingHenries.getF(),leak=config.nativeTransformerLeakageHenries.getF();
                branches.add(new Branch(main,terminals.get(5),p1,"coupled_windings",Map.of("primaryHenries",lm+leak,"secondaryHenries",lm/(ratio*ratio)+leak,"mutualHenries",lm/ratio,"primaryOhms",0.0,"secondaryOhms",0.0),List.of(terminals.get(6),terminals.get(3))));
                var copper=thermal(config.nativeTransformerWindingOhms.getF(),config.nativeTransformerHeatCapacityJoulesPerKelvin.getF(),(core?NativeTransformerGeometry.coolingPerWinding(infrastructure.level,device.pos):config.nativeTransformerCoolingWattsPerKelvin.getF()),393.15);
                for(int winding=0;winding<2;winding++) {
                    UUID heat=branchId(device,"winding-heat/"+winding);devicesByBranch.put(heat,device);
                    branches.add(new Branch(heat,terminals.get(winding==0?4:6),terminals.get(winding==0?5:7),"thermal_fuse",copper));
                }
                // Both voltage probes have finite 1 Gohm input resistance. The primary
                // probe also defines the unpowered terminal voltage when flux is held
                // across an accepted topology boundary (an ideal open winding cannot).
                branches.add(new Branch(branchId(device,"primary-voltage"),p0,p1,"resistor",Map.of("resistanceOhms",1e9)));
                branches.add(new Branch(branchId(device,"primary-meter"),p0,p1,"wattmeter_contact",Map.of("resistanceOhms",0.001),List.of(terminals.get(4))));
                branches.add(new Branch(branchId(device,"secondary-meter"),terminals.get(7),terminals.get(3),"wattmeter_contact",Map.of("resistanceOhms",0.001),List.of(terminals.get(2))));
                branches.add(new Branch(branchId(device,"secondary-voltage"),terminals.get(2),terminals.get(3),"resistor",Map.of("resistanceOhms",1e9)));
            } else if(device instanceof NativeFuseHolder holder) {
                var terminals=java.util.stream.IntStream.range(0,4).mapToObj(n->new PortId(id,n)).toList();
                descriptors.add(new DeviceInput(id,"ports",terminals,Map.of()));
                for(int n=0;n<4;n++)ports.put(new InWorldNode(n,device.pos),terminals.get(n));
                for(var slot:holder.electromagnetism$slots()) {
                    UUID branch=branchId(device,"fuse-holder/"+slot.identity());
                    Map<String,Double> parameters=switch(slot.kind()) {
                        case "fuse" -> FuseParameters.forRating(slot.amperes());
                        case "switch" -> switchContact(slot.closed());
                        case "copper" -> thermal(0.1,20,0.2,393.15);
                        case "indicator" -> NativeProtection.indicator();
                        default -> throw new IllegalArgumentException("Unknown fuse holder attachment "+slot.kind());
                    };
                    if(slot.kind().equals("fuse"))protectiveFuses.add(branch);
                    if(!slot.broken()) {
                        branches.add(new Branch(branch,terminals.get(slot.positive()),terminals.get(slot.negative()),"thermal_fuse",parameters));
                        devicesByBranch.put(branch,device);
                    }
                }
            } else if(device instanceof com.george_vi.electroenergetics.content.fuse.FuseDevice fuse) {
                descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1),Map.of()));
                if(!fuse.isBroken)branches.add(new Branch(main,p0,p1,"thermal_fuse",FuseParameters.forRating(fuse.setAmperage)));
            } else if(device instanceof com.george_vi.electroenergetics.content.energy_meter.EnergyMeterDevice
                    || device instanceof com.george_vi.electroenergetics.content.energy_meter.TriPolarEnergyMeterDevice) {
                var meter=(NativeEnergyMeter)device;
                if(!meter.electromagnetism$counterProblem().isEmpty())throw new IllegalArgumentException(meter.electromagnetism$counterProblem());
                boolean three=device instanceof com.george_vi.electroenergetics.content.energy_meter.TriPolarEnergyMeterDevice;
                boolean closed=three?((com.george_vi.electroenergetics.content.energy_meter.TriPolarEnergyMeterDevice)device).isClosed:((com.george_vi.electroenergetics.content.energy_meter.EnergyMeterDevice)device).isClosed;
                var terminals=java.util.stream.IntStream.range(0,three?6:4).mapToObj(n->new PortId(id,n)).toList();
                descriptors.add(new DeviceInput(id,"ports",terminals,Map.of()));
                for(int n=0;n<terminals.size();n++)ports.put(new InWorldNode(n,device.pos),terminals.get(n));
                double contact=closed?0.001:1e12;
                main=meterBranchId(device,0);
                branches.add(new Branch(main,p0,p1,"wattmeter_contact",Map.of("resistanceOhms",contact),List.of(terminals.get(three?3:2))));
                branches.add(new Branch(branchId(device,"return_contact"),p1,terminals.get(three?4:3),"resistor",Map.of("resistanceOhms",contact)));
                branches.add(new Branch(branchId(device,"voltage_sense/0"),p0,p1,"resistor",Map.of("resistanceOhms",9999.0)));
                if(three) {
                    var second=meterBranchId(device,1);devicesByBranch.put(second,device);
                    branches.add(new Branch(second,terminals.get(2),p1,"wattmeter_contact",Map.of("resistanceOhms",contact),List.of(terminals.get(5))));
                    branches.add(new Branch(branchId(device,"voltage_sense/1"),terminals.get(2),p1,"resistor",Map.of("resistanceOhms",9999.0)));
                }
            } else if(device instanceof com.george_vi.electroenergetics.content.synchroscope.SynchroscopeDevice) {
                var terminals=java.util.stream.IntStream.range(0,6).mapToObj(n->new PortId(id,n)).toList();
                descriptors.add(new DeviceInput(id,"ports",terminals,Map.of()));
                double rating=com.george_vi.electroenergetics.config.CEEConfigs.server().voltageValues.maxVoltmeterVoltage.get();
                double loss=rating*rating/1e6;
                for(int pin=0;pin<6;pin++) {
                    ports.put(new InWorldNode(pin,device.pos),terminals.get(pin));
                    UUID probe=branchId(device,"sync/"+pin);devicesByBranch.put(probe,device);
                    int next=pin/3*3+(pin+1)%3;
                    branches.add(new Branch(probe,terminals.get(pin),terminals.get(next),"thermal_fuse",thermal(1e6,loss/100,loss/100,393.15)));
                }
            } else if(device instanceof com.george_vi.electroenergetics.content.frequency_meter.FrequencyMeterDevice) {
                descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1),Map.of()));
                double rating=com.george_vi.electroenergetics.config.CEEConfigs.server().voltageValues.maxVoltmeterVoltage.get();
                double ratedLoss=rating*rating/1e6;
                branches.add(new Branch(main,p0,p1,"thermal_fuse",thermal(1e6,ratedLoss/100,ratedLoss/100,393.15)));
            } else if(device instanceof com.george_vi.electroenergetics.content.gauge.GaugeDevice gauge) {
                descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1),Map.of()));
                branches.add(new Branch(main,p0,p1,"thermal_fuse",NativeProtection.gauge(gauge.voltmeter)));
            } else if(device instanceof ResistorDevice resistor && resistor.properties!=null) {
                descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1),Map.of()));
                branches.add(new Branch(branchId(device,"main"),p0,p1,resistor.creative?"resistor":"thermal_fuse",
                        resistor.creative?Map.of("resistanceOhms",resistor.properties.resistance):thermal(resistor.properties.resistance,ModConfigs.server().nativeResistorHeatCapacityJoulesPerKelvin.getF(),ModConfigs.server().nativeResistorCoolingWattsPerKelvin.getF(),ModConfigs.server().nativeResistorTripKelvin.getF())));
            } else if(device instanceof HeatingCoilDevice heater) {
                descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1),Map.of()));
                if(!heater.nativeBurned()) {
                    var params=new HashMap<String,Double>();params.put("ambientOhms",(double)ModConfigs.server().heatingCoilResistance.getF());
                    params.put("coefficientPerKelvin",0.0);params.put("heatCapacityJoulesPerKelvin",(double)ModConfigs.server().heatingCoilHeatCapacityJoulesPerKelvin.getF());
                    params.put("conductanceWattsPerKelvin",ModConfigs.server().heatingCoilMaxPower.getF()/(600.0-22.0));params.put("ambientKelvin",295.15);
                    boolean damage=ModConfigs.server().componentDamage.get();if(damage)params.put("tripKelvin",873.15);
                    branches.add(new Branch(main,p0,p1,damage?"thermal_fuse":"thermal_resistor",params));
                }
            } else if(device instanceof NativeLamp lamp) {
                descriptors.add(new DeviceInput(id,"ports",List.of(p0,p1),Map.of()));
                main=branchId(device,"bulb/"+lamp.nativeBulbIdentity());
                if(lamp.hasBulb() && !lamp.isBurned()) {
                    var properties=((ILightBulb)lamp.getBulbItem()).thermalProperties();
                    double rise=properties.operatingTemperature()-LightFixtureDevice.AMBIENT_TEMPERATURE;
                    var parameters=new HashMap<String,Double>();parameters.put("ambientOhms",(double)properties.minResistance());
                    parameters.put("coefficientPerKelvin",(properties.maxResistance()/properties.minResistance()-1)/rise);
                    parameters.put("heatCapacityJoulesPerKelvin",(double)ModConfigs.server().nativeLampHeatCapacityJoulesPerKelvin.getF());
                    parameters.put("conductanceWattsPerKelvin",properties.ratedPower()/rise);
                    parameters.put("ambientKelvin",273.15+LightFixtureDevice.AMBIENT_TEMPERATURE);
                    boolean damage=ModConfigs.server().componentDamage.get();
                    if(damage)parameters.put("tripKelvin",273.15+properties.overheatTemperature());
                    branches.add(new Branch(main,p0,p1,damage?"thermal_fuse":"thermal_resistor",parameters));
                }
            } else {blockers.add("Missing capture adapter: "+device.type.id()+" at "+device.pos);continue;}
            ports.put(new InWorldNode(0,device.pos),p0);ports.put(new InWorldNode(1,device.pos),p1);
            devicesByBranch.put(main,device);
        }
        // Adjacent factory segments share both supply rails through explicit finite contacts.
        for(var device:infrastructure.deviceSD.getDevices()) {
            if(!(device instanceof com.electromagnetism.content.lighting.factory.FactoryLightDevice))continue;
            var state=infrastructure.level.getBlockState(device.pos);
            if(!(state.getBlock() instanceof com.electromagnetism.content.lighting.factory.FactoryLightBlock))continue;
            var axis=state.getValue(com.electromagnetism.content.lighting.factory.FactoryLightBlock.HORIZONTAL_AXIS);
            var neighbour=infrastructure.deviceSD.getDevice(device.pos.relative(net.minecraft.core.Direction.fromAxisAndDirection(axis,net.minecraft.core.Direction.AxisDirection.POSITIVE)));
            if(!(neighbour instanceof com.electromagnetism.content.lighting.factory.FactoryLightDevice))continue;
            var other=infrastructure.level.getBlockState(neighbour.pos);
            if(!(other.getBlock() instanceof com.electromagnetism.content.lighting.factory.FactoryLightBlock)
                    || other.getValue(com.electromagnetism.content.lighting.factory.FactoryLightBlock.HORIZONTAL_AXIS)!=axis)continue;
            for(int terminal=0;terminal<2;terminal++) {
                var a=ports.get(new InWorldNode(terminal,device.pos));var b=ports.get(new InWorldNode(terminal,neighbour.pos));
                if(a==null || b==null){blockers.add("Factory rail owner unloaded: "+device.pos);continue;}
                UUID one=a.device().instance(),two=b.device().instance();
                String key=one.compareTo(two)<0?one+"/"+two:two+"/"+one;
                UUID link=UUID.nameUUIDFromBytes(("factory-rail/"+key+"/"+terminal).getBytes(StandardCharsets.UTF_8));
                branches.add(new Branch(link,a,b,"resistor",Map.of("resistanceOhms",0.001)));
            }
        }
        alternators.finishFields(infrastructure,branches);
        NativeRadioLinks.capture(infrastructure,descriptors,branches,waveforms,identities,captureTime);
        environment.prepareContacts(infrastructure);
        var stableTaps=environment.stableContactIds();
        stableTaps.putAll(trains.prepare(infrastructure));
        for(var device:infrastructure.deviceSD.getDevices()) {
            if(infrastructure.level.getBlockEntity(device.pos) instanceof com.electromagnetism.integration.cee.mixin.NativeContactAccess contact && contact.electromagnetism$attachedNode()!=null)
                stableTaps.put(contact.electromagnetism$attachedNode(),((CeeDeviceIdentity)device).electromagnetism$nativeIdentity().instance());
        }
        var crosses=new IdentityHashMap<WireSimulationState.WireCutHandle,List<UUID>>();
        for(var entry:infrastructure.wireSimulationState.getAllConnections())for(var cut:entry.getValue().cuts)if(cut.handle().name.equals("CrossContact"))crosses.computeIfAbsent(cut.handle(),key->new ArrayList<>()).add(((CeeWireIdentity)entry.getValue().wireData).electromagnetism$wireIdentity());
        for(var entry:infrastructure.wireSimulationState.getAllConnections())for(var cut:entry.getValue().cuts)if(cut.handle().name.equals("CrossContact")) {
            var members=crosses.get(cut.handle());members.sort(UUID::compareTo);String key=members.toString();
            stableTaps.put(cut.node(),UUID.nameUUIDFromBytes(("cross-contact/"+key).getBytes(StandardCharsets.UTF_8)));
        }
        var conductors=NativeConductors.capture(infrastructure,ports,identities,descriptors,branches,wiresByBranch,stableTaps);
        trains.capture(infrastructure,conductors,descriptors,branches,identities);
        catenaryBranches=conductors.catenary();
        for(var contact:infrastructure.deviceSD.getDevices()) {
            com.george_vi.electroenergetics.foundation.nodes.Node attached=null;
            var be=infrastructure.level.getBlockEntity(contact.pos);
            if(contact instanceof com.george_vi.electroenergetics.content.railway_electrification.pantograph.PantographDevice
                    && be instanceof com.george_vi.electroenergetics.content.railway_electrification.pantograph.PantographBlockEntity pantograph)attached=((com.electromagnetism.integration.cee.mixin.NativeContactAccess)pantograph).electromagnetism$attachedNode();
            else if(contact instanceof com.george_vi.electroenergetics.content.railway_electrification.third_rail.RailContactShoeDevice
                    && be instanceof com.george_vi.electroenergetics.content.railway_electrification.third_rail.RailContactShoeBlockEntity shoe)attached=((com.electromagnetism.integration.cee.mixin.NativeContactAccess)shoe).electromagnetism$attachedNode();
            if(attached==null)continue;
            var target=attached instanceof InWorldNode node?ports.get(node):conductors.taps().get(attached);
            if(target==null)throw new IllegalArgumentException("Unresolved current-collection contact: "+attached);
            var origin=ports.get(new InWorldNode(0,contact.pos));
            if(origin==null)throw new IllegalArgumentException("Current-collection device port missing");
            UUID branch=branchId(contact,"main");branches.add(new Branch(branch,origin,target,"thermal_fuse",thermal(0.01,1,0.1,393.15)));devicesByBranch.put(branch,contact);
        }
        probePorts=Map.copyOf(ports);
        environment.capture(infrastructure,ports,identities,descriptors,branches,captureTime);
        environment.captureWireBodies(infrastructure,conductors,descriptors,branches);
        probes.subscribe(infrastructure,ports,branches,waveforms);
        var physicalOwners=new HashMap<DeviceId,SimulatedDevice>();
        for(var device:infrastructure.deviceSD.getDevices())
            if(device instanceof CeeDeviceIdentity identity)physicalOwners.put(identity.electromagnetism$nativeIdentity(),device);
        for(var branch:branches)if(branch.model().equals("thermal_fuse") && !panels.owns(branch.id()) && !wiresByBranch.containsKey(branch.id())) {
            var owner=devicesByBranch.get(branch.id());
            if(owner==null)owner=physicalOwners.get(branch.positive().device());
            if(owner==null)blockers.add("Missing protection consumer: "+branch.id());
            else devicesByBranch.putIfAbsent(branch.id(),owner);
        }
        // Resolve each destructive branch through its actual consumer, never its terminal position.
        // A fuse remains protective even when destructive component damage is disabled.
        branches.replaceAll(branch->{
            if(wiresByBranch.containsKey(branch.id()))
                return NativeProtection.destructive(branch,com.george_vi.electroenergetics.config.CEEConfigs.server().wiresBreak.get());
            var owner=devicesByBranch.get(branch.id());
            if(owner!=null && !protectiveFuses.contains(branch.id()) && owner.type.id().getNamespace().equals("electroenergetics")
                    && !(owner instanceof com.george_vi.electroenergetics.content.fuse.FuseDevice))
                return NativeProtection.destructive(branch,com.george_vi.electroenergetics.config.CEEConfigs.server().componentDamage.get());
            return branch;
        });
        descriptors.sort(Comparator.comparing(d->d.id().instance()));branches.sort(Comparator.comparing(Branch::id));Collections.sort(blockers);
        waveforms.sort(Comparator.comparing(WaveformChannel::channel));
        return new SimulationCoordinator.Frame(descriptors,branches,blockers,RemovalDisposition.EXPORT_STORED_ENERGY,waveforms);
    }
    /** Explicit 0.1 ohm winding/ESR loss and 1 Gohm insulation leakage.
     * Insulation has a one-second thermal time constant and reaches a 100 K
     * steady rise at rated voltage. This replaces the upstream empirical Temp
     * accumulator; neither stored electrical energy nor temperature is imported.
     */
    private void storageParasitics(SimulatedDevice device,PortId positive,PortId negative,PortId internal,double ratedVolts,List<Branch> branches) {
        if(!Double.isFinite(ratedVolts) || ratedVolts<=0)throw new IllegalArgumentException("Positive finite insulation voltage rating required");
        UUID loss=branchId(device,"series_loss"),insulation=branchId(device,"insulation");
        branches.add(new Branch(loss,internal,negative,"thermal_fuse",thermal(0.1,100,1,673.15)));
        double conductance=ratedVolts*ratedVolts/1e11;
        branches.add(new Branch(insulation,positive,internal,"thermal_fuse",thermal(1e9,conductance,conductance,393.15)));
        devicesByBranch.put(loss,device);devicesByBranch.put(insulation,device);
    }
    /** New SI thermal parameters; no conversion of CEE's empirical temperature fields. */
    /** Instantaneous commanded contacts with finite insulation and thermal damage.
     * Valid as a lumped switch without an arc plasma model. Off-state insulation
     * is a physical 1 Tohm path, not a numerical shunt. The same thermal node is
     * retained across command changes; no legacy SwitchingBehaviour is advanced.
     */
    private static Map<String,Double> switchContact(boolean closed) {
        double rating=ModConfigs.server().nativeSwitchRatedAmperes.getF();
        double cooling=rating*rating*0.001/100;
        return thermal(closed?0.001:1e12,cooling,cooling,393.15);
    }
    private static Map<String,Double> thermal(double resistance,double capacity,double conductance,double trip) {
        return NativeProtection.thermal(resistance,capacity,conductance,trip);
    }
    public void tick() {
        commands.tick(infrastructure.level,infrastructure.deviceSD);
        com.electromagnetism.content.instruments.AudioNetworking.tick(infrastructure.level,infrastructure.deviceSD);
        initialize();if(coordinator==null)return;
        // Accept work finished between ticks before submitting another 50 ms input.
        // This gives retained intervals a second nonblocking dispatch opportunity
        // and leaves a quiescent boundary for external energy transactions.
        coordinator.endTick(capture(),this::apply);
        exchangeExternalEffects();
        var frame=capture();
        if(frame.blockers().isEmpty())try {
            if(environment.tickWeather(infrastructure,probePorts,captureTime))frame=capture();
        } catch(IllegalArgumentException | IllegalStateException invalid) {
            frame=new SimulationCoordinator.Frame(List.of(),List.of(),List.of("Invalid weather input: "+invalid.getMessage()),RemovalDisposition.REJECT_ENERGIZED);
        }
        if(!frame.equals(latest)) {
            invalidateForFrame(frame);
        }latest=frame;
        coordinator.tick(frame);infrastructure.setDirty();
    }
    public void endTick() {
        if(coordinator==null)return;
        var live=capture();
        if(!live.equals(latest))invalidateForFrame(live);
        coordinator.endTick(live,this::apply);
        exchangeExternalEffects();
        coordinator.pump(capture());
        var status=coordinator.status().status();
        if(status==Status.REJECTED || status==Status.UNSUPPORTED || status==Status.UNAVAILABLE
                || !coordinator.progress().pauseReason().isEmpty())invalidateReadings();
    }
    private void exchangeExternalEffects() {
        // External handlers run only at a fully accepted, idle boundary. A busy
        // interval must not consume their once-per-game-tick attempt before an
        // accepted completion becomes available later in the same tick.
        if(coordinator==null || !coordinator.canExchangeEnergy())return;
        for(var device:infrastructure.deviceSD.getDevices()) {
            if(!infrastructure.level.isLoaded(device.pos))continue;
            if(infrastructure.level.getBlockEntity(device.pos) instanceof NativePumpBoundary pump)pump.electromagnetism$pump().tick(infrastructure.level.getBlockEntity(device.pos));
            if(infrastructure.level.getBlockEntity(device.pos) instanceof com.george_vi.electroenergetics.content.converter.ConverterBlockEntity be && be instanceof NativeConverterBoundary converter)converter.electromagnetism$converter().tick(be);
        }
    }
    private void invalidateForFrame(SimulationCoordinator.Frame frame) {
        boolean changedBindings=!SimulationCoordinator.sameBindings(frame,latest)
                || !frame.waveforms().equals(latest.waveforms());
        invalidateReadings(changedBindings);
    }
    private void invalidateReadings() {
        invalidateReadings(true);
    }
    private void invalidateReadings(boolean clearInstrumentHistory) {
        readings.clear();
        panels.invalidate();
        alternators.invalidate();
        // A new capture does not revoke an already accepted mechanical impulse.
        // Failures do revoke it; otherwise Create consumes it once or it expires.
        trains.invalidate(infrastructure,clearInstrumentHistory);
        probes.unavailable(infrastructure);
        for(var device:infrastructure.deviceSD.getDevices()) {
            if(!infrastructure.level.getChunkSource().hasChunk(device.pos.getX()>>4,device.pos.getZ()>>4))continue;
            if(infrastructure.level.getBlockEntity(device.pos) instanceof NativeEquipmentDisplay display)display.electromagnetism$displayUnavailable();
            if(infrastructure.level.getBlockEntity(device.pos) instanceof com.electromagnetism.content.electromechanics.excitation.ExcitationStatorBlockEntity field)field.nativeUnavailable();
            if(device instanceof NativeFuseHolder holder)holder.electromagnetism$slotsUnavailable();
            if(infrastructure.level.getBlockEntity(device.pos) instanceof NativeCellDisplay cell)cell.electromagnetism$cellsUnavailable();
            if(infrastructure.level.getBlockEntity(device.pos) instanceof NativeMotorBoundary motor)motor.electromagnetism$motor(0,0,0,false);
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
    private List<UUID> apply(Observations observations) {
        environment.apply(infrastructure,observations);
        if(observations.accepted().end()==observations.accepted().start())invalidateReadings(false);
        if(observations.accepted().end()>observations.accepted().start()) {
            readings.clear();for(var reading:observations.readings()) {
                panels.accept(reading);
                readings.put(reading.branch(),reading);
                var owner=devicesByBranch.get(reading.branch());
                if(owner instanceof com.george_vi.electroenergetics.content.transmission_distribution.hv_switch.HVSwitchDevice
                        && infrastructure.level.getBlockEntity(owner.pos) instanceof NativeHvDisplay hv)hv.electromagnetism$arc(reading.temperatureKelvin().orElse(0),reading.temperatureKelvin().isPresent());
                if(owner instanceof com.george_vi.electroenergetics.content.frequency_meter.FrequencyMeterDevice && infrastructure.level.getBlockEntity(owner.pos) instanceof NativeFrequencyDisplay frequency)frequency.electromagnetism$frequency(reading.frequency());
                if(owner instanceof com.george_vi.electroenergetics.content.transmission_distribution.sf6_breaker.SF6BreakerDevice breaker) {
                    boolean closed=coordinator.acceptedBranch(reading.branch()).orElseThrow().parameters().get("ambientOhms")<1;
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
                        && infrastructure.level.getBlockEntity(gauge.pos) instanceof NativeGauge consumer)consumer.electromagnetism$acceptGauge(reading);
            }
        }
        for(var device:new HashSet<>(devicesByBranch.values())) {
            if(infrastructure.level.getBlockEntity(device.pos) instanceof NativeEquipmentDisplay display) {
                var values=new ArrayList<NativeEquipmentDisplay.Value>();
                if(device instanceof com.george_vi.electroenergetics.content.converter.ConverterDevice) {
                    var buffer=readings.get(branchId(device,"fe-buffer"));
                    if(buffer!=null){values.add(new NativeEquipmentDisplay.Value("electromagnetism.measurement.buffer_voltage",buffer.rmsVoltage(),"V"));values.add(new NativeEquipmentDisplay.Value("electromagnetism.measurement.stored_energy",ModConfigs.server().nativeConverterBufferJoules.getF()*Math.pow(buffer.rmsVoltage()/48,2),"J"));}
                } else if(device instanceof com.george_vi.electroenergetics.content.transmission_distribution.voltage_regulator.VoltageRegulatorDevice regulator && regulator.bottom) {
                    var input=readings.get(branchId(device,"regulator/input-voltage"));var output=readings.get(branchId(device,"regulator/output-voltage"));
                    if(input!=null&&output!=null){values.add(new NativeEquipmentDisplay.Value("electromagnetism.measurement.input_rms",input.rmsVoltage(),"V"));values.add(new NativeEquipmentDisplay.Value("electromagnetism.measurement.output_rms",output.rmsVoltage(),"V"));}
                }
                if(!(device instanceof com.george_vi.electroenergetics.content.rotor.AlternatorBrushesDevice) && !(device instanceof com.george_vi.electroenergetics.content.rotor.ThreePhaseAlternatorBrushesDevice))display.electromagnetism$display(values,observations.accepted().start(),observations.accepted().end());
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
                    if(device instanceof com.george_vi.electroenergetics.content.transmission_distribution.transformer.TransformerCoreDevice) {
                        var state=infrastructure.level.getBlockState(device.pos);
                        var partnerPos=device.pos.relative(state.getValue(com.george_vi.electroenergetics.content.transmission_distribution.transformer.TransformerCoreBlock.FACING));
                        double dissipation=2*100*NativeTransformerGeometry.coolingPerWinding(infrastructure.level,device.pos);
                        if(display instanceof NativeCoreCooling cooling)cooling.electromagnetism$coolingLimit(dissipation);
                        if(infrastructure.level.getBlockEntity(partnerPos) instanceof NativeTransformerDisplay other) {
                            if(other instanceof NativeCoreCooling cooling)cooling.electromagnetism$coolingLimit(dissipation);
                            other.electromagnetism$acceptTransformer(primary.rmsVoltage(),voltage.rmsVoltage(),Math.abs(secondary.activePower()));
                        }
                    }
                    display.electromagnetism$acceptTransformer(primary.rmsVoltage(),voltage.rmsVoltage(),Math.abs(secondary.activePower()));
                }
            }
            if(device instanceof NativeEnergyMeter && infrastructure.level.getBlockEntity(device.pos) instanceof NativeEnergyDisplay display) {
                int count=device instanceof com.george_vi.electroenergetics.content.energy_meter.TriPolarEnergyMeterDevice?2:1;
                double watts=0,joules=0;boolean valid=true;
                for(int leg=0;leg<count;leg++) {var reading=readings.get(meterBranchId(device,leg));if(reading==null){valid=false;break;}watts+=reading.activePower();joules+=reading.totalAbsorbedEnergy()-reading.totalReturnedEnergy();}
                if(valid)display.electromagnetism$acceptEnergy(watts,joules);else display.electromagnetism$energyUnavailable();
            }
        }
        for(var device:infrastructure.deviceSD.getDevices()) {
            if(device instanceof com.george_vi.electroenergetics.content.electric_motor.ElectricMotorDevice
                    && infrastructure.level.getBlockEntity(device.pos) instanceof NativeMotorBoundary motor) {
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
        alternators.apply(readings,observations.accepted().start(),observations.accepted().end());
        trains.apply(infrastructure,readings,observations.accepted());
        for(var waveform:observations.waveforms()) {
            probes.publish(infrastructure,waveform);
            for(var device:devicesByBranch.values()) {
                if((device instanceof com.electromagnetism.content.instruments.OscilloscopeDevice || device instanceof com.electromagnetism.content.instruments.SignalGeneratorDevice || device instanceof com.electromagnetism.content.instruments.RadioDevice)
                        && ((CeeDeviceIdentity)device).electromagnetism$nativeIdentity().equals(waveform.positive().device())
                        && infrastructure.level.getBlockEntity(device.pos) instanceof CeeWaveformConsumer consumer) {
                    consumer.electromagnetism$publishWaveform(waveform);break;
                }
            }
        }
        var beforeEvents=observations.events().isEmpty()?List.<Branch>of():capture().branches();
        var confirmed=new ArrayList<UUID>();
        // Validate the entire event boundary before applying its first effect.
        for(var event:observations.events())if(event.id().getLeastSignificantBits()>confirmedEvent) {
            var owner=devicesByBranch.get(event.branch());
            if(event.action()!=EventAction.DAMAGE && !(owner instanceof com.george_vi.electroenergetics.content.relay.RelayDevice))
                throw new IllegalStateException("Missing relay event consumer: "+event.branch());
            if(event.action()==EventAction.DAMAGE && owner==null && !wiresByBranch.containsKey(event.branch()) && !panels.owns(event.branch()))
                throw new IllegalStateException("Missing protection event consumer: "+event.branch());
        }
        for(var event:observations.events()) {
            long ordinal=event.id().getLeastSignificantBits();
            if(ordinal>confirmedEvent) {
                var wire=wiresByBranch.get(event.branch());var device=devicesByBranch.get(event.branch());
                if(event.action()!=EventAction.DAMAGE) {
                    if(!(device instanceof com.george_vi.electroenergetics.content.relay.RelayDevice relay))throw new IllegalStateException("Missing relay event consumer");
                    relay.closed=event.action()==EventAction.ENERGIZE;
                    if(relay.behaviour!=null)relay.behaviour.isClosed=relay.closed^relay.inverted;
                    infrastructure.level.playSound(null,device.pos,net.minecraft.sounds.SoundEvents.LEVER_CLICK,net.minecraft.sounds.SoundSource.BLOCKS,0.1f,relay.closed?1.0f:0.8f);
                    infrastructure.deviceSD.setDirty();
                }
                else if(panels.destroy(event.branch())) { /* Attachment event applied at the native boundary. */ }
                else if(wire!=null) {if(catenaryBranches.contains(event.branch()))infrastructure.removeCatenary(wire.node1().sourcePos(),wire.node2().sourcePos());else if(infrastructure.getConnectionData(wire)!=null)infrastructure.removeConnection(wire);}
                else if(device!=null && infrastructure.deviceSD.getDevice(device.pos)==device) {
                    if(device instanceof NativeFuseHolder holder) {
                        var slot=holder.electromagnetism$slots().stream().filter(part->event.branch().equals(branchId(device,"fuse-holder/"+part.identity()))).findFirst().orElseThrow();
                        holder.electromagnetism$slotBurn(slot.identity());
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
                            if(infrastructure.level.getBlockState(part.pos).equals(states.get(member)))
                                infrastructure.level.destroyBlock(part.pos,true);
                            if(infrastructure.deviceSD.getDevice(part.pos)==part)infrastructure.deviceSD.removeDevice(part.pos);
                        }
                    }
                    else {infrastructure.level.destroyBlock(device.pos,true);infrastructure.deviceSD.removeDevice(device.pos);}
                }
                confirmedEvent=ordinal;readings.remove(event.branch());
            }
            confirmed.add(event.id());
        }
        if(!confirmed.isEmpty()) {
            var afterEffects=capture();
            var remaining=afterEffects.branches().stream().map(Branch::id).collect(java.util.stream.Collectors.toSet());
            coordinator.confirmDestroyedBoundary(beforeEvents.stream().map(Branch::id).filter(id->!remaining.contains(id)).sorted().toList(),afterEffects);
        }
        infrastructure.setDirty();return confirmed;
    }
    public CompoundTag save() {
        if(closed)return retained==null?null:retained.copy();
        if(coordinator==null || coordinator.status().status()==Status.UNAVAILABLE)return retained==null?null:retained.copy();
        Snapshot snapshot=coordinator.checkpoint(capture(),this::apply);
        var tag=new CompoundTag();var revision=snapshot.revision();tag.putUUID("Session",revision.session());
        tag.putLong("Topology",revision.topology());tag.putLong("Parameters",revision.parameters());tag.putLong("Sequence",revision.sequence());
        tag.putString("ModMetadata",snapshot.modVersion());tag.putDouble("AcceptedTime",snapshot.acceptedTime());tag.putByteArray("State",snapshot.payload());
        tag.putByteArray("InputHistory",coordinator.inputHistoryCheckpoint());
        tag.put("Environment",environment.save());
        tag.putLong("ConfirmedEvent",confirmedEvent);retained=tag;return tag.copy();
    }
    @Override public void close() {
        if(closed)return;
        save();infrastructure.setDirty();infrastructure.level.getDataStorage().save();
        if(coordinator!=null)coordinator.close();if(backend!=null)backend.close();closed=true;readings.clear();
    }
}
