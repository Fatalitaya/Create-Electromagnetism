package com.electromagnetism.integration.cee.nativebridge;

import java.util.Map;

/** Lumped fuse wire: 1 mOhm, 100 K trip rise, one-second thermal time constant.
 * Rated current reaches the threshold only asymptotically. Twice rated current
 * trips after -ln(3/4) s at ambient. The former dimensionless Temp is not imported.
 * On melt, remaining heat is dissipated at the acknowledged destruction boundary;
 * replacing the wire creates a cold branch after that boundary.
 */
public final class FuseParameters {
    private FuseParameters() {}
    public static Map<String,Double> forRating(double amperes) {
        if(!Double.isFinite(amperes) || amperes<=0)throw new IllegalArgumentException("Fuse rating must be finite and positive in amperes");
        double cooling=amperes*amperes*0.001/100;
        return Map.of("ambientOhms",0.001,"coefficientPerKelvin",0.0,
                "heatCapacityJoulesPerKelvin",cooling,"conductanceWattsPerKelvin",cooling,
                "ambientKelvin",293.15,"tripKelvin",393.15);
    }
}
