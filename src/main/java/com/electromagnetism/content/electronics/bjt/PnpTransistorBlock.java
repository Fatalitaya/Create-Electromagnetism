package com.electromagnetism.content.electronics.bjt;

import com.electromagnetism.registry.ModDeviceTypes;
import com.george_vi.electroenergetics.devices.device.SimulatedDeviceType;

/** The PNP variant keeps NPN terminal order and mirrors operating voltage and current directions. */
public class PnpTransistorBlock extends TransistorBlock {
    public PnpTransistorBlock(Properties properties) {
        super(properties);
    }

    @Override
    public boolean isPnp() {
        return true;
    }

    @Override
    public SimulatedDeviceType<TransistorDevice> getDevice() {
        return ModDeviceTypes.PNP_TRANSISTOR.get();
    }
}
