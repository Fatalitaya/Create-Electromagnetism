package com.electromagnetism.integration.cee.nativebridge;

import java.util.Map;

/**
 * Calibrate forward-active Vbe at Ic = 1 mA and channel resistance at 1 V gate overdrive and small
 * Vds. The 10 mΩ output lead has a 100 K rated rise and a one-second thermal time constant. The
 * model includes junction charge.
 */
public final class SemiconductorParameters {
    private SemiconductorParameters() {}
    public static Map<String,Double> bipolar(double beta,double vbeAtMilliamp) {
        return Map.of("saturationAmperes",0.001/Math.expm1(vbeAtMilliamp/0.02585),
                "thermalVolts",0.02585,"forwardBeta",beta,"reverseBeta",1.0,
                "baseEmitterFarads",1e-9,"baseCollectorFarads",1e-10);
    }
    public static Map<String,Double> mosfet(double threshold,double resistanceAtOneVoltOverdrive) {
        return Map.of("betaAmperesPerVoltSquared",1/resistanceAtOneVoltOverdrive,
                "thresholdVolts",threshold,"saturationAmperes",1e-12,"thermalVolts",0.02585,
                "gateSourceFarads",1e-9,"gateDrainFarads",1e-10);
    }
    public static Map<String,Double> outputProtection(double ratedAmperes) {
        if(!Double.isFinite(ratedAmperes) || ratedAmperes<=0)throw new IllegalArgumentException("Positive finite semiconductor current rating required");
        double cooling=ratedAmperes*ratedAmperes*0.01/100;
        return Map.of("ambientOhms",0.01,"coefficientPerKelvin",0.0,
                "heatCapacityJoulesPerKelvin",cooling,"conductanceWattsPerKelvin",cooling,
                "ambientKelvin",295.15,"tripKelvin",395.15);
    }
}
