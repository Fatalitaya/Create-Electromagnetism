package com.electromagnetism.integration.cee.nativebridge;
import com.electromagnetism.simulation.SimulationProtocol.*;
import net.minecraft.world.item.Item;
import java.util.UUID;
/** Presentation of one replaceable bulb; thermal state remains native-owned. */
public interface NativeLamp {
    UUID nativeBulbIdentity();
    Item getBulbItem();
    boolean hasBulb();
    boolean isBurned();
    void acceptNative(Reading reading);
    void acceptNativeBurn(ProtectionEvent event);
    void nativeUnavailable();
}
