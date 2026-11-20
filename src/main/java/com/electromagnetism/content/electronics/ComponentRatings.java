package com.electromagnetism.content.electronics;

/** Nameplate values used by item tooltips and electrical model calibration. */
public final class ComponentRatings {
    private ComponentRatings() {}

    public record Rectifier(double peakReverseVolts, double continuousForwardAmperes) {}

    public static final Rectifier DIODE = new Rectifier(100, 5);
    public static final Rectifier HIGH_VOLTAGE_RECTIFIER = new Rectifier(4000, 10);

    public static final double ZENER_VOLTS = 56;
    public static final double ZENER_TEST_AMPERES = 0.020;
    public static final double ZENER_DYNAMIC_OHMS = 2;
    public static final double ZENER_CONTINUOUS_WATTS = 5;

    public static final double CHOKE_HENRIES = 0.4;
    public static final double CHOKE_WINDING_OHMS = 0.4;
    public static final double CHOKE_CONTINUOUS_AMPERES = 10;
    public static final double CHOKE_INSULATION_VOLTS = 500;
}
