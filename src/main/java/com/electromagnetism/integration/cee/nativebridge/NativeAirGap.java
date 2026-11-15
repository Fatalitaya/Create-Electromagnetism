package com.electromagnetism.integration.cee.nativebridge;
import java.util.Map;
public final class NativeAirGap {
    private NativeAirGap(){}
    public static Map<String,Double> parameters(boolean closed,double breakdownVolts,double distanceMetres){
        if(!Double.isFinite(breakdownVolts)||breakdownVolts<=0||!Double.isFinite(distanceMetres)||distanceMetres<0)throw new IllegalArgumentException("Invalid air gap geometry");
        double length=Math.max(0.01,distanceMetres);
        return Map.of("closed",closed?1.0:0.0,"contactOhms",0.01,"leakageSiemens",1e-12,"hotSiemens",1.0/(50*length),
                "breakdownVolts",breakdownVolts,"ignitionRiseKelvin",1000.0,"heatCapacityJoulesPerKelvin",0.01*length,
                "conductanceWattsPerKelvin",0.1*length,"ambientKelvin",293.15);
    }
}
