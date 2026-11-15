package com.electromagnetism.simulation;

import java.util.Objects;
import java.util.UUID;

/** A receipt for an accepted native world effect, not a mechanical state solver.
 * Create consumes at most this impulse during its next tick. Queries never spend
 * it; unused effects expire rather than driving a later, different boundary. */
public final class AcceptedImpulse {
    private UUID session;
    private double through=-1, impulse;
    private long issuedTick=Long.MIN_VALUE;

    public void accept(UUID session,double start,double end,double meanForce,long tick) {
        Objects.requireNonNull(session);
        if(!Double.isFinite(start)||!Double.isFinite(end)||!Double.isFinite(meanForce)
                ||start<0||end<start||end-start>0.05+1e-9)
            throw new IllegalArgumentException("Invalid accepted traction interval");
        if(!session.equals(this.session)){clear();this.session=session;through=-1;}
        if(end<=through)return;
        if(start<through)throw new IllegalArgumentException("Overlapping traction receipt");
        expire(tick);
        // New publications must not renew the lifetime of unspent older effects.
        if(issuedTick!=tick)clear();
        double next=impulse+meanForce*(end-start);
        if(!Double.isFinite(next))throw new IllegalArgumentException("Nonfinite accepted impulse");
        impulse=next;through=end;issuedTick=tick;
    }

    public void expire(long tick) {
        if(issuedTick!=Long.MIN_VALUE && (tick<issuedTick || tick-issuedTick>1))clear();
    }

    public double available(double direction) {return Math.max(0,Math.signum(direction)*impulse);}

    /** Returns the signed impulse actually spent. */
    public double consume(double requested) {
        if(!Double.isFinite(requested))throw new IllegalArgumentException("Nonfinite impulse request");
        double spent=Math.copySign(Math.min(Math.abs(requested),available(requested)),requested);
        impulse-=spent;return spent;
    }

    public void clear(){impulse=0;issuedTick=Long.MIN_VALUE;}
}
