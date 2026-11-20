package com.electromagnetism.integration.cee.nativebridge;

import java.util.Map;

/**
 * The 1 mΩ fuse wire has a 100 K equilibrium rise at rated current and a 1 s thermal time
 * constant. From ambient, twice rated current trips it in −ln(3/4) s. Settle remaining heat after
 * destruction is acknowledged and start the replacement cold.
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
