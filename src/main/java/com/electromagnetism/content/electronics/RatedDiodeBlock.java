package com.electromagnetism.content.electronics;

import com.george_vi.electroenergetics.content.electronic_components.diode.DiodeBlock;
import com.george_vi.electroenergetics.content.electronic_components.diode.DiodeDevice;
import com.george_vi.electroenergetics.devices.device.SimulatedDeviceType;
import java.util.function.Supplier;

/** Rated diodes use CEE's terminal layout, placement orientation and polarity convention. */
public final class RatedDiodeBlock extends DiodeBlock {
    private final Supplier<SimulatedDeviceType<DiodeDevice>> deviceType;

    public RatedDiodeBlock(Properties properties, Supplier<SimulatedDeviceType<DiodeDevice>> deviceType) {
        super(properties);
        this.deviceType = deviceType;
    }

    @Override
    public SimulatedDeviceType<DiodeDevice> getDevice() {
        return deviceType.get();
    }
}
