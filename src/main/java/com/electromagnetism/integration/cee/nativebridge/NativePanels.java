package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.SimulationProtocol.*;
import com.george_vi.electroenergetics.content.electrical_panel.ElectricalPanelDevice;
import com.george_vi.electroenergetics.content.electrical_panel.attachments.*;
import com.george_vi.electroenergetics.content.electrical_panel.special_interaction.*;
import com.george_vi.electroenergetics.foundation.nodes.InWorldNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import com.electromagnetism.integration.cee.display.RmsDisplayWindow;

/** The panel owns the external ports. Each attachment owns its internal branches. */
public final class NativePanels {
    private final Map<UUID,Consumer<Reading>> consumers=new HashMap<>();
    private final Map<UUID,Runnable> destruction=new HashMap<>();
    private final Map<DeviceId,List<Runnable>> invalidators=new HashMap<>();
    private final Map<UUID,GaugePanelAttachment> gauges = new HashMap<>();
    public void begin(){gauges.clear();consumers.clear();destruction.clear();invalidators.clear();}
    public void invalidate(){invalidators.values().forEach(list->list.forEach(Runnable::run));}
    public void invalidate(Set<DeviceId> owners){owners.forEach(id->invalidators.getOrDefault(id,List.of()).forEach(Runnable::run));}
    public void accept(Reading reading,Function<Reading,RmsDisplayWindow.Sample> displaySample) {
        var gauge=gauges.get(reading.branch());
        if(gauge!=null) {
            var sample=displaySample.apply(reading);
            if(sample!=null && sample.end()==reading.windowEnd()) {
                gauge.value=gauge.voltmeter?sample.voltage():sample.current();
                gauge.sendData();
            }
        }
        var sink=consumers.get(reading.branch());if(sink!=null)sink.accept(reading);
    }
    public boolean owns(UUID branch){return destruction.containsKey(branch);}
    public boolean destroy(UUID branch){var effect=destruction.get(branch);if(effect==null)return false;effect.run();return true;}
    private static UUID branch(PanelAttachment part,String role){return UUID.nameUUIDFromBytes((((NativePanelPart)part).electromagnetism$partId()+"/"+role).getBytes(StandardCharsets.UTF_8));}
    private static Map<String,Double> contact(boolean closed,double rated){
        double cooling=0.001*rated*rated/100;
        return Map.of("ambientOhms",closed?0.001:1e12,"coefficientPerKelvin",0.0,"heatCapacityJoulesPerKelvin",cooling,
                "conductanceWattsPerKelvin",cooling,"ambientKelvin",293.15,"tripKelvin",393.15);
    }
    public void capture(ElectricalPanelDevice panel,DeviceId id,List<DeviceInput> devices,Map<InWorldNode,PortId> ports,List<Branch> branches) {
        var terminals=new ArrayList<PortId>();
        var identities=new HashSet<UUID>();
        for(int slot=0;slot<panel.attachments.length;slot++) {
            var part=panel.attachments[slot];if(part==null)continue;
            var identity=(NativePanelPart)part;
            if(!identity.electromagnetism$partProblem().isEmpty())throw new IllegalArgumentException(identity.electromagnetism$partProblem());
            if(!identities.add(identity.electromagnetism$partId()))throw new IllegalArgumentException("Duplicate panel attachment identity at "+panel.pos);
            var p=new ArrayList<PortId>();
            for(var node:part.nodes) {
                if(!node.sourcePos().equals(panel.pos))throw new IllegalArgumentException("Foreign panel attachment port");
                var port=new PortId(id,node.id());p.add(port);
                if(ports.putIfAbsent(node,port)!=null)throw new IllegalArgumentException("Overlapping panel attachment ports at "+panel.pos);
                terminals.add(port);
            }
            UUID main=branch(part,"main");
            int index=slot;
            destruction.put(main,()->{if(panel.attachments[index]==part){part.onRemoved(null);panel.attachments[index]=null;part.sendData();}});
            int firstBranch=branches.size();
            if(part instanceof GaugePanelAttachment gauge) {
                requirePorts(p,2);branches.add(new Branch(main,p.get(0),p.get(1),"thermal_fuse",NativeProtection.gauge(gauge.voltmeter)));
                gauges.put(main,gauge);
                invalidators.computeIfAbsent(id,key->new ArrayList<>()).add(()->{gauge.value=Double.NaN;gauge.sendData();});
            } else if(part instanceof IndicatorBulbPanelAttachment) {
                requirePorts(p,2);branches.add(new Branch(main,p.get(0),p.get(1),"thermal_fuse",NativeProtection.indicator()));
                consumers.put(main,r->((NativePanelIndicator)part).electromagnetism$light((float)Math.min(1,r.rmsVoltage()/70)));
                invalidators.computeIfAbsent(id,key->new ArrayList<>()).add(()->((NativePanelIndicator)part).electromagnetism$light(0));
            } else if(part instanceof CutOffSwitchPanelAttachment cutoff) {
                requirePorts(p,2);branches.add(new Branch(main,p.get(0),p.get(1),"thermal_fuse",contact(cutoff.isClosed,100)));
            } else if(part instanceof EStopPanelAttachment stop) {
                requirePorts(p,2);branches.add(new Branch(main,p.get(0),p.get(1),"thermal_fuse",contact(stop.isClosed,100)));
            } else if(part instanceof MomentarySwitchPanelAttachment button) {
                requirePorts(p,2);branches.add(new Branch(main,p.get(0),p.get(1),"thermal_fuse",contact(button.closedTicks>0,100)));
            } else if(part instanceof MCBPanelAttachment breaker) {
                requirePorts(p,2);if(breaker.setAmperage<=0)throw new IllegalArgumentException("Panel breaker rating must be positive");
                branches.add(new Branch(main,p.get(0),p.get(1),"thermal_fuse",contact(breaker.isClosed,breaker.setAmperage)));
                destruction.put(main,()->{if(panel.attachments[index]==part){breaker.isClosed=false;identity.electromagnetism$renewElectricalIdentity();breaker.sendData();}});
            } else if(part instanceof BaseEnergyMeterAttachment meter) {
                var counter=(NativeEnergyMeter)meter;
                if(!counter.electromagnetism$counterProblem().isEmpty())throw new IllegalArgumentException(counter.electromagnetism$counterProblem());
                boolean three=part instanceof TriPolarEnergyMeterAttachment;
                requirePorts(p,three?6:4);
                double resistance=meter.disconnected?1e12:0.001;
                var legs=new ArrayList<UUID>();
                int count=three?2:1,reference=three?4:3;
                for(int leg=0;leg<count;leg++) {
                    int input=three?(leg==0?3:5):2,output=three?(leg==0?0:2):0;
                    UUID meterId=branch(part,"counter/"+counter.electromagnetism$counterIdentity()+"/"+leg);legs.add(meterId);
                    branches.add(new Branch(meterId,p.get(input),p.get(reference),"wattmeter_contact",Map.of("resistanceOhms",resistance),List.of(p.get(output))));
                }
                branches.add(new Branch(main,p.get(reference),p.get(1),"resistor",Map.of("resistanceOhms",resistance)));
                var accepted=new HashMap<UUID,Reading>();
                Consumer<Reading> consume=r->{
                    accepted.put(r.branch(),r);if(accepted.size()!=legs.size())return;
                    double watts=0,joules=0;
                    for(var value:accepted.values()) {
                        if(value.windowStart()!=r.windowStart() || value.windowEnd()!=r.windowEnd())return;
                        watts+=value.activePower();joules+=value.totalAbsorbedEnergy()-value.totalReturnedEnergy();
                    }
                    meter.activePower=watts;meter.totalEnergy=joules/3_600_000;meter.sendData();
                };
                for(var leg:legs)consumers.put(leg,consume);
                invalidators.computeIfAbsent(id,key->new ArrayList<>()).add(()->{meter.activePower=Double.NaN;meter.sendData();});
            } else if(!(part instanceof AnalogLeverPanelAttachment || part instanceof SteeringWheelPanelAttachment
                    || part instanceof AltitudeSensorPanelAttachment || part instanceof VelocitySensorPanelAttachment)) {
                throw new IllegalArgumentException("Missing native panel attachment adapter: "+part.type);
            }
            if(!(part instanceof MCBPanelAttachment)) {
                boolean damage=com.george_vi.electroenergetics.config.CEEConfigs.server().componentDamage.get();
                for(int i=firstBranch;i<branches.size();i++)branches.set(i,NativeProtection.destructive(branches.get(i),damage));
            }
        }
        devices.add(new DeviceInput(id,"ports",terminals,Map.of()));
    }
    private static void requirePorts(List<PortId> ports,int count){if(ports.size()!=count)throw new IllegalArgumentException("Unexpected panel attachment terminal count");}
}
