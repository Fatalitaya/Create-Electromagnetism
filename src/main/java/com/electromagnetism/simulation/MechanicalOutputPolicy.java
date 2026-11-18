package com.electromagnetism.simulation;

import java.util.*;
import com.electromagnetism.simulation.SimulationCoordinator.Frame;
import static com.electromagnetism.simulation.SimulationProtocol.*;

public final class MechanicalOutputPolicy {
    private MechanicalOutputPolicy() {}
    public static boolean isCurrent(Frame live,Frame historical,UUID id) {
        if(live==null || historical==null)return false;
        var old=historical.branches().stream().filter(b->b.id().equals(id)).findFirst();
        var now=live.branches().stream().filter(b->b.id().equals(id)).findFirst();
        if(old.isEmpty() || !old.equals(now))return false;
        var devices=new HashSet<DeviceId>();devices.add(old.get().positive().device());devices.add(old.get().negative().device());
        old.get().additionalPorts().forEach(p->devices.add(p.device()));
        for(var device:devices)if(!Objects.equals(historical.recipients().mechanicalNetworks().get(device),live.recipients().mechanicalNetworks().get(device)))return false;
        for(var assembly:historical.recipients().assemblies())if(assembly.phases().contains(id)) {
            if(live.recipients().assemblies().stream().noneMatch(current->current.identity().equals(assembly.identity())
                    && current.members().equals(assembly.members()) && current.phases().equals(assembly.phases())))return false;
            for(UUID member:assembly.members()) {
                var device=new DeviceId(member,0);
                if(!Objects.equals(historical.recipients().mechanicalNetworks().get(device),live.recipients().mechanicalNetworks().get(device)))return false;
            }
        }
        return true;
    }

}
