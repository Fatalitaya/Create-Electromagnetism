package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.*;
import com.george_vi.electroenergetics.devices.device.SimulatedDevice;
import com.george_vi.electroenergetics.foundation.nodes.*;
import com.george_vi.electroenergetics.simulation.infrastructure.*;
import java.util.*;
import static com.electromagnetism.simulation.SimulationProtocol.*;


final class NativeDeviceCapture {
    private static final List<NativeDeviceAdapter> ADAPTERS=List.of(
            new NativeMachineAdapter(),new NativeInstrumentAdapter(),new NativeConnectionAdapter(),
            new NativeStorageAdapter(),new NativeElectronicAdapter(),new NativeSwitchAdapter(),new NativeLoadAdapter());
    private final NativeCaptureContext context;
    NativeDeviceCapture(NativeCaptureContext context) {this.context=context;}
    void capture(SimulatedDevice device,DeviceId id) {
        for(var adapter:ADAPTERS)if(adapter.capture(context,device,id))return;
        throw new IllegalArgumentException("Missing capture adapter: "+device.type.id());
    }
}
