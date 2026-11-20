package com.electromagnetism.content.lighting.bulb;

/**
 * Bulb items provide the ratings and thermal parameters used to construct the fixture's circuit
 * model.
 */
public interface ILightBulb {
    int LIGHT_LEVEL_LOW_POWER = 10;
    int LIGHT_LEVEL_FULL_POWER = 15;

    /**
     * Filament calibration between ambient and rated operating temperature, with the bulb's
     * display capabilities.
     *
     * @param dissipationFactor rated power divided by rated temperature rise, in W/K
     * @param thermalMass filament thermal inertia parameter
     * @param overheatTemperature overheat threshold, in °C
     * @param minResistance cold resistance, in Ω
     * @param maxResistance resistance at the rated operating point, in Ω
     * @param operatingTemperature rated operating temperature, in °C
     * @param ratedPower rated input power, in W
     * @param ratedVoltage rated terminal voltage, in V
     * @param breakdownVoltage breakdown threshold, in V
     * @param maxPowerLevel highest supported brightness level
     * @param dyeable whether the bulb shell accepts dye
     */
    record ThermalProperties(float dissipationFactor, float thermalMass, float overheatTemperature, float minResistance, float maxResistance, float operatingTemperature, float ratedPower, float ratedVoltage, float breakdownVoltage, int maxPowerLevel, boolean dyeable) {
    }

    ThermalProperties thermalProperties();

    /**
     * Derive hot resistance from rated voltage and power. Calibrate cooling at 22 °C ambient and
     * 1450 °C operating temperature.
     */
    static ThermalProperties fromRated(float ratedPower, float ratedVoltage, float minResistance, float thermalMass, int maxPowerLevel, boolean dyeable) {
        float rMax = ratedVoltage * ratedVoltage / ratedPower;
        float operatingTemperature = 1450f;
        float dissipationFactor = ratedPower / (operatingTemperature - 22f);
        return new ThermalProperties(dissipationFactor, thermalMass, operatingTemperature + 400f, minResistance, rMax, operatingTemperature, ratedPower, ratedVoltage, ratedVoltage * 1.5f, maxPowerLevel, dyeable);
    }
}
