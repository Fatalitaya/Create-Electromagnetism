package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.SimulationProtocol.Reading;
import java.util.List;

/**
 * Compare zero crossings of two sets of line voltages from the same accepted window for the
 * three-phase display.
 */
public record NativeThreePhaseReading(double degrees,boolean correctOrder) {
    private static double angle(double value){return Math.atan2(Math.sin(value),Math.cos(value));}
    private static double phaseAtEnd(Reading r){return r.phase().orElseThrow()+2*Math.PI*r.frequency().orElseThrow()*(r.windowEnd()-r.windowStart());}
    private static int order(List<Reading> r,int offset) {
        double frequency=r.get(offset).frequency().orElseThrow();
        for(int n=1;n<3;n++)if(Math.abs(r.get(offset+n).frequency().orElseThrow()/frequency-1)>0.01)return 0;
        double a=angle(phaseAtEnd(r.get(offset+1))-phaseAtEnd(r.get(offset)));
        double b=angle(phaseAtEnd(r.get(offset+2))-phaseAtEnd(r.get(offset)));
        // Allow moderate imbalance. Missing, collinear or severely unbalanced phases invalidate
        // the phase-order reading.
        if(Math.abs(a)<Math.PI/3 || Math.abs(b)<Math.PI/3 || Math.abs(a)>5*Math.PI/6 || Math.abs(b)>5*Math.PI/6 || a*b>=0)return 0;
        return a<0?1:-1;
    }
    public static NativeThreePhaseReading from(List<Reading> readings) {
        if(readings.size()!=6 || readings.stream().anyMatch(r->r==null || r.frequency().isEmpty() || r.phase().isEmpty() || r.frequency().orElseThrow()<=0 || r.rmsVoltage()<1e-6))return new NativeThreePhaseReading(Double.NaN,false);
        var first=readings.getFirst();
        if(readings.stream().anyMatch(r->r.windowStart()!=first.windowStart() || r.windowEnd()!=first.windowEnd()))return new NativeThreePhaseReading(Double.NaN,false);
        int primary=order(readings,0),secondary=order(readings,3);
        if(primary==0 || secondary==0)return new NativeThreePhaseReading(Double.NaN,false);
        double degrees=Math.toDegrees(angle(phaseAtEnd(readings.get(3))-phaseAtEnd(first)));
        return new NativeThreePhaseReading(degrees,primary==secondary);
    }
}
