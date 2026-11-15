package com.electromagnetism.integration.cee.nativebridge;

import java.util.*;
import com.electromagnetism.simulation.SimulationProtocol.Reading;
public interface NativeFuseHolder {
    record Slot(UUID identity,String kind,int positive,int negative,boolean broken,boolean closed,double amperes) {}
    List<Slot> electromagnetism$slots();
    void electromagnetism$replaceSlotCommands();
    void electromagnetism$slotReading(UUID identity,Reading reading);
    void electromagnetism$slotsUnavailable();
    void electromagnetism$slotBurn(UUID identity);
}
