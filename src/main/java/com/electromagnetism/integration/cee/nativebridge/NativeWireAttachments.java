package com.electromagnetism.integration.cee.nativebridge;

import com.george_vi.electroenergetics.CEEWireAttachments;
import com.george_vi.electroenergetics.simulation.infrastructure.WireData;

/** The four audited attachment types have presentation/drop behavior only: no
 * electrical port, stored energy, or electrical feedback callback. New types
 * require an explicit adapter; attaching decoration preserves conductor state.
 */
public final class NativeWireAttachments {
    private NativeWireAttachments() {}
    public static void validate(WireData wire) {
        if(wire.attachments().size()>64)throw new IllegalArgumentException("Wire attachment count exceeds 64");
        for(var entry:wire.attachments()) {
            if(entry==null || entry.getFirst()==null || !Float.isFinite(entry.getFirst()) || entry.getFirst()<0 || entry.getFirst()>1 || entry.getSecond()==null)
                throw new IllegalArgumentException("Wire attachment position must be finite and in [0,1]");
            var type=entry.getSecond().type;
            if(type!=CEEWireAttachments.EMPTY.get() && type!=CEEWireAttachments.BANNER.get() && type!=CEEWireAttachments.BUNTING.get() && type!=CEEWireAttachments.DAMPER.get())
                throw new IllegalArgumentException("Unknown wire attachment coupling: "+type);
        }
    }
}
