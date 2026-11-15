package com.electromagnetism.integration.cee.nativebridge;
public interface NativeCellDisplay {
    void electromagnetism$cell(int index,double stateOfCharge,double meanStoredJoules);
    void electromagnetism$cellsUnavailable();
}
