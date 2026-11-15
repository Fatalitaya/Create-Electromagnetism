package com.electromagnetism.integration.cee.nativebridge;
import java.util.UUID;
public interface NativeEnergyMeter {
    UUID electromagnetism$counterIdentity();
    String electromagnetism$counterProblem();
    void electromagnetism$resetCounter();
    void electromagnetism$validatePersistedCounter(net.minecraft.nbt.CompoundTag data);
}
