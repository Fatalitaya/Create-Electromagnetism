package com.electromagnetism.core.model;

/** Physical quantities and their display units; energy inputs are in Wh. */
public final class MeterUnits {
    private MeterUnits() {}
    public record Reading(double value, String unit) {}

    public static Reading automatic(double value, String baseUnit) {
        double magnitude = Math.abs(value);
        boolean mega = !baseUnit.equals("V") && !baseUnit.equals("A");
        double size = mega && magnitude >= 1e6 ? 1e6 : magnitude >= 1000 ? 1000
                : magnitude > 0 && magnitude < 1 ? 0.001 : 1;
        String prefix = size == 1e6 ? "M" : size == 1000 ? "k" : size == 0.001 ? "m" : "";
        return new Reading(value / size, prefix + baseUnit);
    }

}
