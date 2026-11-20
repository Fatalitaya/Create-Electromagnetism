package com.electromagnetism.simulation;

import java.util.Objects;
import java.util.UUID;

/**
 * Hold accepted native impulse for Create to consume, subject to the remaining amount and its
 * expiry.
 */
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
        // Publishing new impulse must not extend the lifetime of an older balance.
        if(issuedTick!=tick)clear();
        double next=impulse+meanForce*(end-start);
        if(!Double.isFinite(next))throw new IllegalArgumentException("Nonfinite accepted impulse");
        impulse=next;through=end;issuedTick=tick;
    }

    public void expire(long tick) {
        if(issuedTick!=Long.MIN_VALUE && (tick<issuedTick || tick-issuedTick>1))clear();
    }

    public double available(double direction) {return Math.max(0,Math.signum(direction)*impulse);}

    /** Return the signed impulse actually spent. */
    public double consume(double requested) {
        if(!Double.isFinite(requested))throw new IllegalArgumentException("Nonfinite impulse request");
        double spent=Math.copySign(Math.min(Math.abs(requested),available(requested)),requested);
        impulse-=spent;return spent;
    }

    public void clear(){impulse=0;issuedTick=Long.MIN_VALUE;}
}
