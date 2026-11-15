package com.electromagnetism.integration.cee.nativebridge;

import java.util.List;

/** Accepted observations for presentation only; never restored as physical state. */
public interface NativeEquipmentDisplay {
    record Value(String label,double value,String unit) {}
    void electromagnetism$display(List<Value> values,double start,double end);
    default void electromagnetism$displayUnavailable(){electromagnetism$display(List.of(),0,0);}
}
