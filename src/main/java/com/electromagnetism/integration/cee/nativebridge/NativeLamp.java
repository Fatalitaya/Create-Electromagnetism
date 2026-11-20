package com.electromagnetism.integration.cee.nativebridge;
import com.electromagnetism.simulation.SimulationProtocol.*;
import net.minecraft.world.item.Item;
import java.util.UUID;
/** Display the accepted thermal state of the currently installed bulb. */
public interface NativeLamp {
    UUID nativeBulbIdentity();
    Item getBulbItem();
    boolean hasBulb();
    boolean isBurned();
    void acceptNative(Reading reading);
    void acceptNativeBurn(ProtectionEvent event);
    void nativeUnavailable();
}
