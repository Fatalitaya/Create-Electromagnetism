package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.*;
import com.george_vi.electroenergetics.devices.device.SimulatedDevice;
import com.george_vi.electroenergetics.foundation.nodes.*;
import com.george_vi.electroenergetics.simulation.infrastructure.*;
import net.minecraft.nbt.*;
import net.neoforged.fml.ModList;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.electromagnetism.simulation.SimulationProtocol.*;

/** Main-thread CEE facts and accepted-result application. No legacy electrical callbacks. */
public final class CeeNativeWorld implements AutoCloseable {
    private final InfrastructureSavedData infrastructure;
    private JniSimulationBackend backend;
    private FaultDomainManager coordinator;
    private CompoundTag retained;
    private Diagnostic failure;
    private final SimulationFaultLog faults=new SimulationFaultLog();
    private SimulationCoordinator.Frame latest;
    private final NativeWorldCapture inputs;
    private final NativeWorldDelivery delivery;
    private boolean closed;
    private final NativeWorldCommands commands=new NativeWorldCommands();
    private final NativeVoltageProbes probes=new NativeVoltageProbes();
    private final NativePanels panels=new NativePanels();
    private final NativeAlternators alternators=new NativeAlternators();
    private final NativeRadioLinks radioLinks=new NativeRadioLinks();
    private final NativeEnvironment environment=new NativeEnvironment();
    private final NativeTrains trains=new NativeTrains();
    public void probe(net.minecraft.server.level.ServerPlayer player,InWorldNode node) {
        capture();
        probes.select(infrastructure,player,node,inputs.probePorts());
    }
    public CeeNativeWorld(InfrastructureSavedData infrastructure){
        this.infrastructure=infrastructure;
        inputs=new NativeWorldCapture(infrastructure,panels,alternators,radioLinks,environment,trains,probes,()->infrastructure.level.getGameTime()*0.05,()->latest);
        delivery=new NativeWorldDelivery(infrastructure,panels,alternators,environment,trains,probes,inputs.wires(),this::capture);
    }
    public boolean exchangeEnergy(UUID branch,double joules,double maximumJoules,boolean commit){
        if(coordinator==null || closed || failure!=null)return false;
        boolean result=coordinator.exchangeEnergy(capture(),branch,joules,maximumJoules,commit);
        if(result && commit){delivery.invalidateReadings(false);infrastructure.setDirty();}
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
        try {
            FaultDomainManager.Saved saved=null;
            if(retained!=null) {
                saved=NativeWorldPersistence.decodeDomains(retained);
                delivery.restoreEvents(retained.getList("ConfirmedEvents",Tag.TAG_COMPOUND));
                if(retained.contains("AssemblyIdentities",Tag.TAG_LIST))alternators.restoreIdentities(retained.getList("AssemblyIdentities",Tag.TAG_COMPOUND));
            }
            coordinator=new FaultDomainManager(backend,saved);
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
    public Optional<FaultDomainManager.TickReport> progress(){return coordinator==null || closed?Optional.empty():Optional.of(coordinator.progress());}
    public void resumeMissingInputs() {
        if(coordinator==null || closed || failure!=null)throw new IllegalStateException("Native world unavailable");
        coordinator.endTick(capture(),delivery);
        coordinator.resumeMissingInputs(capture());infrastructure.setDirty();
    }
    public Optional<Reading> reading(UUID branch){return delivery.reading(branch);}

    public SimulationCoordinator.Frame capture(){return inputs.capture();}
    private long lastWorldTick=Long.MIN_VALUE;
    private static final class ServerBudget {
        int tick;
        long usedNanos;
    }
    private static final java.util.WeakHashMap<net.minecraft.server.MinecraftServer,ServerBudget> budgets=new java.util.WeakHashMap<>();
    public void tick() {
        long worldTick=infrastructure.level.getGameTime();
        if(lastWorldTick==worldTick)return;
        lastWorldTick=worldTick;
        var server=infrastructure.level.getServer();
        var budget=budgets.computeIfAbsent(server,key->new ServerBudget());
        int tick=server.getTickCount();
        if(budget.tick!=tick){budget.tick=tick;budget.usedNanos=0;}
        long started=System.nanoTime();
        long allowance=(long)(com.electromagnetism.config.ModConfigs.server().nativeTickBudgetMillis.getF()*1_000_000.0);
        try {tick(started+Math.max(0L,allowance-budget.usedNanos));}
        finally {budget.usedNanos+=System.nanoTime()-started;}
    }
    private void tick(long deadline) {
        commands.tick(infrastructure.level,infrastructure.deviceSD);
        com.electromagnetism.content.instruments.AudioNetworking.tick(infrastructure.level,infrastructure.deviceSD);
        initialize();if(coordinator==null || failure!=null || closed)return;
        coordinator.beginWorldTick(infrastructure.level.getGameTime(),deadline);
        // Resolve retained effects before capturing the next synchronous interval.
        coordinator.endTick(capture(),delivery);
        exchangeExternalEffects();
        var frame=capture();
        if(frame.blockers().isEmpty())try {
            if(environment.tickWeather(infrastructure,inputs.probePorts(),inputs.inputTime()))frame=capture();
        } catch(IllegalArgumentException | IllegalStateException invalid) {
            faults.report("Capturing electrical weather "+infrastructure.level.dimension().location(),new Diagnostic(Status.REJECTED,invalid.getMessage()),invalid);
            frame=new SimulationCoordinator.Frame(List.of(),List.of(),List.of("Invalid weather input: "+invalid.getMessage()),RemovalDisposition.REJECT_ENERGIZED);
        }
        if(!frame.equals(latest)) {
            delivery.invalidateForFrame(latest,frame);
        }latest=frame;
        coordinator.tick(frame);
        coordinator.endTick(capture(),delivery);
        infrastructure.setDirty();
    }
    public void endTick() {
        if(coordinator==null || failure!=null || closed)return;
        var live=capture();
        if(!live.equals(latest))delivery.invalidateForFrame(latest,live);
        coordinator.endTick(live,delivery);
        exchangeExternalEffects();
        delivery.invalidateFaultedRecipients(latest,coordinator.faultedRecipients());
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
    public CompoundTag save() {
        if(closed)return retained==null?null:retained.copy();
        if(failure!=null || coordinator==null || coordinator.status().status()==Status.UNAVAILABLE)return retained==null?null:retained.copy();
        var saved=coordinator.checkpoint(capture(),delivery);
        if(saved.domains().isEmpty())return retained==null?null:retained.copy();
        var tag=NativeWorldPersistence.encode(saved,environment.save(),alternators.saveIdentities(),delivery.saveEvents());
        retained=tag;return tag.copy();
    }
    @Override public void close() {
        if(closed)return;
        save();infrastructure.setDirty();infrastructure.level.getDataStorage().save();
        if(coordinator!=null)coordinator.close();if(backend!=null)backend.close();closed=true;delivery.clear();
    }
}
