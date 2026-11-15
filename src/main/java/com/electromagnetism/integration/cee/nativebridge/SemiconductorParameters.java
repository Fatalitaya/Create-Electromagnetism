package com.electromagnetism.integration.cee.nativebridge;

import java.util.Map;

/** Fixed-temperature engineering calibration. SI throughout; junction charge is never zero.
 * Vbe is specified at Ic=1 mA in forward active operation. Rds is the small-Vds
 * channel resistance at one volt of gate overdrive, not a constant switched resistor.
 * The output lead is a 10 mOhm thermal cutout with a one-second thermal time constant.
 * Its steady trip rise is 100 K at rated current; at twice rating it trips in
 * -ln(3/4) seconds starting at ambient. Junction avalanche is outside this model.
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
