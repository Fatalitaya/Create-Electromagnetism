package com.electromagnetism.integration.cee.nativebridge;

import com.electromagnetism.simulation.SimulationProtocol.Reading;
import java.util.List;

/** Read-only presentation of native line-voltage crossing observations. No local
 * clock, signal integration, or physical phase state. Both groups share the same
 * accepted window; unequal group frequencies intentionally rotate the display.
 */
public record NativeThreePhaseReading(double degrees,boolean correctOrder) {
    private static double angle(double value){return Math.atan2(Math.sin(value),Math.cos(value));}
    private static double phaseAtEnd(Reading r){return r.phase().orElseThrow()+2*Math.PI*r.frequency().orElseThrow()*(r.windowEnd()-r.windowStart());}
    private static int order(List<Reading> r,int offset) {
        double frequency=r.get(offset).frequency().orElseThrow();
        for(int n=1;n<3;n++)if(Math.abs(r.get(offset+n).frequency().orElseThrow()/frequency-1)>0.01)return 0;
        double a=angle(phaseAtEnd(r.get(offset+1))-phaseAtEnd(r.get(offset)));
        double b=angle(phaseAtEnd(r.get(offset+2))-phaseAtEnd(r.get(offset)));
        // Line-voltage phase order remains meaningful for moderate unbalance;
        // collinear/missing phases and extreme unbalance are not certified.
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
