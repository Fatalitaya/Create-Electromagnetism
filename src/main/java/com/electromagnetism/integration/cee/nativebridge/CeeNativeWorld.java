package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.*;
import com.george_vi.electroenergetics.devices.device.SimulatedDevice;
import com.george_vi.electroenergetics.simulation.infrastructure.*;
import net.minecraft.nbt.*;
import net.neoforged.fml.ModList;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.electromagnetism.simulation.SimulationProtocol.*;

/** Capture CEE inputs and deliver accepted results on the world thread. */
public final class CeeNativeWorld implements AutoCloseable {
    private final InfrastructureSavedData infrastructure;
    private JniSimulationBackend backend;
    private FaultDomainManager coordinator;
    private CompoundTag retained;
    private final ListTag quarantinedDomains=new ListTag();
    private final Set<UUID> quarantinedSessions=new HashSet<>();
    private Diagnostic failure;
    private final SimulationFaultLog faults=new SimulationFaultLog();
    private SimulationCoordinator.Frame latest;
    private final NativeWorldCapture inputs;
    private final NativeWorldDelivery delivery;
    private boolean closed;
    private final NativeWorldCommands commands=new NativeWorldCommands();
    private final NativePanels panels=new NativePanels();
    private final NativeAlternators alternators=new NativeAlternators();
    private final NativeRadioLinks radioLinks=new NativeRadioLinks();
    private final NativeEnvironment environment=new NativeEnvironment();
    private final NativeTrains trains=new NativeTrains();
    public CeeNativeWorld(InfrastructureSavedData infrastructure){
        this.infrastructure=infrastructure;
        inputs=new NativeWorldCapture(infrastructure,panels,alternators,radioLinks,environment,trains,()->infrastructure.level.getGameTime()*0.05,()->latest);
        delivery=new NativeWorldDelivery(infrastructure,panels,alternators,environment,trains,inputs.wires(),this::capture);
    }
    public boolean exchangeEnergy(UUID branch,double joules,double maximumJoules,boolean commit){
        if(coordinator==null || closed || failure!=null)return false;
        boolean result=coordinator.exchangeEnergy(capture(),branch,joules,maximumJoules,commit);
        if(result && commit){delivery.invalidateElectrical(latest,coordinator.membersForBranch(branch));infrastructure.setDirty();}
        return result;
    }
    public static UUID branchId(SimulatedDevice device,String role) {
        if(role.equals("main") && device instanceof com.electromagnetism.content.instruments.RadioDevice radio)role+=radio.transmitter?"/transmitter":"/receiver";
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
        if(backend.availability().status()!=Status.READY) {
            failure=backend.availability();
            return;
        }
        try {
            coordinator=new FaultDomainManager(backend,null);
            if(retained!=null) {
                NativeWorldPersistence.restoreDomains(retained,metadata,coordinator,quarantinedDomains,quarantinedSessions);
                delivery.restoreEvents(retained.getList("ConfirmedEvents",Tag.TAG_COMPOUND));
                if(retained.contains("AssemblyIdentities",Tag.TAG_LIST))alternators.restoreIdentities(retained.getList("AssemblyIdentities",Tag.TAG_COMPOUND));
            }
            delivery.retainPendingEvents(coordinator,quarantinedSessions);
            var recorded=coordinator.lastRecordedFrame();
            if(recorded!=null && (retained==null || !retained.contains("AssemblyIdentities",Tag.TAG_LIST)))alternators.restore(recorded.recipients().assemblies());
        } catch(RuntimeException invalid) {
            coordinator=null;
            try {backend.close();}catch(RuntimeException cleanup){invalid.addSuppressed(cleanup);}
            backend=null;failure=new Diagnostic(Status.UNAVAILABLE,"Native world restore failed: "+invalid.getMessage());
            faults.report("Restoring electrical world "+infrastructure.level.dimension().location(),failure,invalid);
        }
    }
    public void restore(CompoundTag tag) {
        if(coordinator!=null || retained!=null)throw new IllegalStateException("Native world already has an owner");
        retained=tag.copy();
        try {
            NativeWorldPersistence.requireMetadata(tag);
            if(tag.contains("Environment",Tag.TAG_COMPOUND))environment.restore(tag.getCompound("Environment"));
        } catch(IllegalArgumentException | IllegalStateException invalid) {
            failure=new Diagnostic(Status.UNAVAILABLE,"Native environment restore failed: "+invalid.getMessage());
            faults.report("Restoring electrical environment "+infrastructure.level.dimension().location(),failure,invalid);
        }
    }
    public Diagnostic status(){return closed?new Diagnostic(Status.CLOSED,"Native world closed"):failure!=null?failure:coordinator==null?NativeSimulation.UNAVAILABLE:coordinator.status();}
    private long captureStartedNanos, captureToDeliveryNanos;
    public Optional<FaultDomainManager.TickReport> progress(){
        if(coordinator==null || closed)return Optional.empty();
        return Optional.of(coordinator.progress(captureToDeliveryNanos));
    }
    public OptionalDouble displayCurrent(UUID branch){return delivery.displayCurrent(branch);}
    public Optional<Reading> reading(UUID branch){return delivery.reading(branch);}

    public SimulationCoordinator.Frame capture(){return inputs.capture();}
    private long lastWorldTick=Long.MIN_VALUE;
    public void tick() {
        long worldTick=infrastructure.level.getGameTime();
        if(lastWorldTick==worldTick)return;
        lastWorldTick=worldTick;
        commands.tick(infrastructure.level,infrastructure.deviceSD);
        com.electromagnetism.content.instruments.AudioNetworking.tick(infrastructure.level,infrastructure.deviceSD);
        initialize();if(coordinator==null || failure!=null || closed)return;
        coordinator.beginWorldTick(worldTick);
        captureStartedNanos=System.nanoTime();
        var frame=capture();
        long mutations=delivery.worldMutations();
        if(coordinator.readyForDelivery())coordinator.endTick(frame,delivery);
        if(exchangeExternalEffects() || mutations!=delivery.worldMutations())frame=capture();
        if(frame.blockers().isEmpty())try {
            if(environment.tickWeather(infrastructure,inputs.nodePorts(),inputs.inputTime()))frame=capture();
        } catch(IllegalArgumentException | IllegalStateException invalid) {
            faults.report("Capturing electrical weather "+infrastructure.level.dimension().location(),new Diagnostic(Status.REJECTED,invalid.getMessage()),invalid);
            frame=new SimulationCoordinator.Frame(List.of(),List.of(),List.of("Invalid weather input: "+invalid.getMessage()),RemovalDisposition.REJECT_ENERGIZED);
        }
        boolean changed=!frame.equals(latest);
        if(changed)delivery.invalidateForFrame(latest,frame);
        latest=frame;
        coordinator.tick(frame,delivery);
        captureToDeliveryNanos=System.nanoTime()-captureStartedNanos;
        infrastructure.setDirty();
    }
    public void endTick() {
        if(coordinator==null || failure!=null || closed)return;
        long mutations=delivery.worldMutations();
        var live=capture();
        if(!live.equals(latest))delivery.invalidateForFrame(latest,live);
        coordinator.endTick(live,delivery);
        exchangeExternalEffects();
        if(delivery.worldMutations()!=mutations)captureToDeliveryNanos=System.nanoTime()-captureStartedNanos;
        delivery.retainPendingEvents(coordinator,quarantinedSessions);
        var faulted=coordinator.faultedRecipients();
        if(!faulted.isEmpty())delivery.invalidateFaultedRecipients(latest,faulted);
    }
    private boolean exchangeExternalEffects() {
        // External handlers run at an idle, accepted boundary. A busy interval leaves this tick's
        // attempt available for later delivery.
        if(coordinator==null || !coordinator.canExchangeEnergy())return false;
        boolean touched=false;
        for(var device:infrastructure.deviceSD.getDevices()) {
            if(!infrastructure.level.isLoaded(device.pos))continue;
            var entity=infrastructure.level.getBlockEntity(device.pos);
            if(entity instanceof NativePumpBoundary pump) {
                pump.electromagnetism$pump().tick(entity);
                touched=true;
            }
            if(entity instanceof com.george_vi.electroenergetics.content.converter.ConverterBlockEntity be && be instanceof NativeConverterBoundary converter) {
                converter.electromagnetism$converter().tick(be);
                touched=true;
            }
        }
        return touched;
    }
    public CompoundTag save() {
        if(closed)return retained==null?null:retained.copy();
        if(failure!=null || coordinator==null || backend.availability().status()!=Status.READY)return retained==null?null:retained.copy();
        var saved=coordinator.checkpoint();
        if(saved.domains().isEmpty())return retained==null?null:retained.copy();
        delivery.retainPendingEvents(coordinator,quarantinedSessions);
        var tag=NativeWorldPersistence.encode(saved,environment.save(),alternators.saveIdentities(),delivery.saveEvents());
        tag.getList("Domains",Tag.TAG_COMPOUND).addAll(quarantinedDomains);
        retained=tag;return tag.copy();
    }
    @Override public void close() {
        if(closed)return;
        save();infrastructure.setDirty();infrastructure.level.getDataStorage().save();
        if(coordinator!=null)coordinator.close();if(backend!=null)backend.close();closed=true;delivery.clear();
    }
}
