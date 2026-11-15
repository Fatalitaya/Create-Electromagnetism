package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.SimulationProtocol.Diagnostic;

/** Exposed by SimulationTicker; callers must not read obsolete lastResults arrays. */
public interface CeeSimulationAccess {
    boolean electromagnetism$exchangeEnergy(java.util.UUID branch,double joules,double maximumJoules,boolean commit);
    void electromagnetism$probe(net.minecraft.server.level.ServerPlayer player, com.george_vi.electroenergetics.foundation.nodes.InWorldNode node);
    Diagnostic electromagnetism$nativeStatus();
    java.util.Optional<com.electromagnetism.simulation.SimulationCoordinator.Progress> electromagnetism$nativeProgress();
    void electromagnetism$resumeMissingInputs();
    net.minecraft.nbt.CompoundTag electromagnetism$saveNative();
    void electromagnetism$restoreNative(net.minecraft.nbt.CompoundTag state);
    void electromagnetism$closeNative();
    java.util.Optional<com.electromagnetism.simulation.SimulationProtocol.Reading> electromagnetism$reading(java.util.UUID branch);

    /** Server-thread diagnostic only, not a solver input submission. */
    CeeInputInventory electromagnetism$inspectInputs();
}
