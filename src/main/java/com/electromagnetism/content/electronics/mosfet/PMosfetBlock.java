package com.electromagnetism.content.electronics.mosfet;

import com.electromagnetism.registry.ModDeviceTypes;
import com.george_vi.electroenergetics.devices.device.SimulatedDeviceType;

/**
 * Mirror gate-source drive and body diode polarity for the P-channel variant while retaining
 * N-channel terminal numbering.
 */
public class PMosfetBlock extends MosfetBlock {
    public PMosfetBlock(Properties properties) {
        super(properties);
    }

    @Override
    public boolean isPChannel() {
        return true;
    }

    @Override
    public SimulatedDeviceType<MosfetDevice> getDevice() {
        return ModDeviceTypes.P_MOSFET.get();
    }
}
