package com.electromagnetism.integration.cee.nativebridge;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;

/** Panel attachment identities are independent of mounting coordinates and slot numbers. */
public interface NativePanelPart {
    UUID electromagnetism$partId();
    String electromagnetism$partProblem();
    void electromagnetism$renewElectricalIdentity();
    void electromagnetism$initializePart();
    void electromagnetism$readPart(CompoundTag envelope);
    void electromagnetism$writePart(CompoundTag envelope);
}
