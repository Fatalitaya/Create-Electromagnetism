package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.*;
import com.george_vi.electroenergetics.devices.device.SimulatedDevice;
import com.george_vi.electroenergetics.foundation.nodes.*;
import com.george_vi.electroenergetics.simulation.infrastructure.*;
import java.util.*;
import static com.electromagnetism.simulation.SimulationProtocol.*;


record NativeCaptureContext(InfrastructureSavedData infrastructure,
        NativePanels panels,
        NativeAlternators alternators,
        Map<UUID,SimulatedDevice> devicesByBranch,
        Map<SimulatedDevice,List<SimulatedDevice>> damageAssemblies,
        Set<UUID> protectiveFuses,
        double captureTime,
        Map<net.minecraft.core.BlockPos,List<InWorldNode>> nodesByPosition,
        List<DeviceInput> descriptors,
        List<Branch> branches,
        List<WaveformChannel> waveforms,
        Map<InWorldNode,PortId> ports,
        Set<DeviceId> identities) {}
