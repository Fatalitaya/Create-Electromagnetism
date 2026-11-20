package com.electromagnetism.config;

import net.createmod.catnip.config.ConfigBase;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.function.Supplier;

/**
 * Bind server parameters to Create's config specification for device registration and world
 * adapters.
 */
public class ModConfigs {
    private static CServer server;

    public static CServer server() {
        return server;
    }

    /** Build field definitions and the NeoForge specification with the same builder. */
    private static <T extends ConfigBase> T register(Supplier<T> factory) {
        Pair<T, ModConfigSpec> specPair = new ModConfigSpec.Builder().configure(builder -> {
            T config = factory.get();
            config.registerAll(builder);
            return config;
        });
        T config = specPair.getLeft();
        config.specification = specPair.getRight();
        return config;
    }

    public static void register(ModContainer container) {
        server = register(CServer::new);
        container.registerConfig(ModConfig.Type.SERVER, server.specification);
    }

    public static class CServer extends ConfigBase {
        public final ConfigFloat nativePumpPressurePascals=f(100000,1,1e8f,"nativePumpPressurePascals","Fixed hydraulic pressure boundary in Pa; 1000 mB equals one litre.");
        public final ConfigFloat nativePumpEfficiency=f(0.8f,0.01f,1,"nativePumpEfficiency","Electrical-to-hydraulic efficiency at the pump boundary.");
        public final ConfigInt nativePumpMillibucketsPerTick=i(100,1,1000,"nativePumpMillibucketsPerTick","Maximum direct adjacent-handler fluid transfer per game tick.");
        public final ConfigFloat nativeBodyOhms=f(1000,100,1000000,"nativeBodyOhms","Lumped body-to-earth resistance in ohms for gameplay contact exposure.");
        public final ConfigFloat nativeShockJoulesPerDamage=f(10,0.001f,1000000,"nativeShockJoulesPerDamage","Accepted body dissipation in joules per gameplay damage unit; not a medical model.");
        public final ConfigFloat nativeLightningRiseSeconds=f(0.00001f,0.000001f,0.01f,"nativeLightningRiseSeconds","Lightning double-exponential rise time in physical seconds.");
        public final ConfigFloat nativeLightningFallSeconds=f(0.001f,0.00002f,0.1f,"nativeLightningFallSeconds","Lightning double-exponential decay time in physical seconds; must exceed rise time.");
        public final ConfigFloat nativeTorquePerBaseStress=f(1,0.000001f,1000000,"nativeTorquePerBaseStress","Chosen Create boundary scale in N m per base stress unit; total Create stress includes RPM.");
        public final ConfigFloat nativeMotorTorqueConstant=f(1,0.000001f,10000,"nativeMotorTorqueConstant","Reciprocal motor constant in N m/A and V s/rad.");
        public final ConfigFloat nativeMotorWindingOhms=f(1,0.0001f,10000,"nativeMotorWindingOhms","Motor winding resistance in ohms.");
        public final ConfigFloat nativeMotorWindingHenries=f(0.05f,0.000001f,100,"nativeMotorWindingHenries","Motor winding inductance in H.");
        public final ConfigFloat nativeJoulesPerFe=f(1,0.000001f,1000000,"nativeJoulesPerFe","Explicit external FE boundary conversion in J/FE.");
        public final ConfigFloat nativeConverterBufferJoules=f(100000,1,1e9f,"nativeConverterBufferJoules","Converter DC-link energy capacity in J, independent of legacy StoredEnergy.");
        public final ConfigFloat nativeConverterEfficiency=f(0.9f,0.01f,1,"nativeConverterEfficiency","Bidirectional converter efficiency; all losses enter the native ledger.");
        public final ConfigFloat nativeConverterMaxWatts=f(100000,1,1e9f,"nativeConverterMaxWatts","External FE transfer limit in W at the game-clock boundary.");
        public final ConfigFloat nativeCellFullVolts=f(12,0.1f,1000,"nativeCellFullVolts","Cell full-charge open-circuit voltage in V; new cells start discharged.");
        public final ConfigFloat nativeCellCapacityCoulombs=f(36000,1,1e8f,"nativeCellCapacityCoulombs","Usable charge in C for the linear OCV cell model; independent of legacy CellCharge fields.");
        public final ConfigFloat nativeCellResistanceOhms=f(0.05f,0.0001f,100,"nativeCellResistanceOhms","Cell copper resistance in ohms.");
        public final ConfigFloat nativeSwitchRatedAmperes=f(100,0.001f,100000,"nativeSwitchRatedAmperes","Contact thermal rating in A at 1 mOhm, 100 K trip rise and one-second thermal time constant; no arc plasma model.");
        public final ConfigFloat nativeTransformerMagnetizingHenries=f(1,0.001f,1000,"nativeTransformerMagnetizingHenries","Primary-referred linear magnetizing inductance in H; no core saturation.");
        public final ConfigFloat nativeTransformerLeakageHenries=f(0.001f,0.000001f,1,"nativeTransformerLeakageHenries","Leakage inductance per winding in H.");
        public final ConfigFloat nativeTransformerWindingOhms=f(0.1f,0.0001f,100,"nativeTransformerWindingOhms","Copper resistance per winding in ohms, modeled at its thermal port.");
        public final ConfigFloat nativeTransformerHeatCapacityJoulesPerKelvin=f(100,0.001f,100000,"nativeTransformerHeatCapacityJoulesPerKelvin","Thermal capacity per winding in J/K.");
        public final ConfigFloat nativeTransformerCoolingWattsPerKelvin=f(1,0.001f,10000,"nativeTransformerCoolingWattsPerKelvin","Winding cooling to 293.15 K ambient in W/K; trip at 393.15 K.");
        public final ConfigFloat nativeTransformerFinCoolingWattsPerKelvin=f(1,0.001f,10000,"nativeTransformerFinCoolingWattsPerKelvin","Additional ambient conductance in W/K per connected core cooling fin within four metres, shared by both windings.");
        public final ConfigFloat nativeTransformerLiquidCoolingWattsPerKelvin=f(1,0.001f,10000,"nativeTransformerLiquidCoolingWattsPerKelvin","Additional ambient conductance in W/K per water/oil-immersed core half; liquid is an ambient-temperature boundary.");
        public final ConfigFloat heatingCoilHeatCapacityJoulesPerKelvin=f(200,0.001f,100000,"heatingCoilHeatCapacityJoulesPerKelvin","Lumped coil heat capacity in J/K; not legacy mass.");
        public final ConfigFloat nativeLampHeatCapacityJoulesPerKelvin=f(2,0.001f,10000,"nativeLampHeatCapacityJoulesPerKelvin","Bulb heat capacity in J/K, independent of legacy thermalMass.");
        public final ConfigFloat nativePoleConductorOhmsPerMetre=f(0.002f,0.000001f,10,"nativePoleConductorOhmsPerMetre","Concrete pole internal conductor in ohms per metre; each end-ring side is one metre equivalent, each vertical block adds one metre.");
        public final ConfigFloat nativeWireHeatCapacityJoulesPerKelvinMetre=f(100,0.001f,100000,"nativeWireHeatCapacityJoulesPerKelvinMetre","Wire heat capacity in J/(K m).");
        public final ConfigFloat nativeWireCoolingWattsPerKelvinMetre=f(1,0.001f,10000,"nativeWireCoolingWattsPerKelvinMetre","Wire cooling in W/(K m).");
        public final ConfigFloat nativeWireTripKelvin=f(673.15f,294,3000,"nativeWireTripKelvin","Wire trip temperature in kelvin; ambient 293.15 K.");
        public final ConfigFloat nativeResistorHeatCapacityJoulesPerKelvin=f(100,0.001f,100000,"nativeResistorHeatCapacityJoulesPerKelvin","Resistor heat capacity in J/K.");
        public final ConfigFloat nativeResistorTripKelvin=f(673.15f,294,3000,"nativeResistorTripKelvin","Resistor trip temperature in kelvin.");
        public final ConfigEnum<com.electromagnetism.core.model.AnalysisMode> analysisMode = e(
                com.electromagnetism.core.model.AnalysisMode.AUTO, "analysisMode",
                "AUTO selects validated spectral/envelope states; TRANSIENT uses adaptive time integration.",
                "Unsupported spectral devices stay on the audited transient adapter with diagnostics.");
        /** Increasing the radius increases the number of blocks visited per scan. */
        public final ConfigInt growthLampRadius = i(2, 1, 8, "growthLampRadius",
                "Radius of the area affected by the growth lamp");
        /** Each tagged block gets an independent chance of a random tick. */
        public final ConfigInt growthLampChance = i(50, 0, 400, "growthLampChance",
                "Chance denominator for ticking a crop (lower = more frequent). 1 ticks every block every tick.");
        /** Control the world damage effect when an eligible component overloads. */
        public final ConfigBool componentDamage = b(true, "componentDamage",
                "Light bulbs and heating coils burn out when overloaded");
        /** Maximum length of the light column maintained below a fixture, in blocks. */
        public final ConfigInt factoryLightProjectionRange = i(16, 0, 32, "factoryLightProjectionRange",
                "Maximum block range of the factory light projected light blocks");
        /** Heating coil series resistance, in ohms. */
        public final ConfigFloat heatingCoilResistance = f(32.3f, 0.1f, 10000, "heatingCoilResistance",
                "Series resistance of the heating coil");
        /** Input power in watts required for equilibrium at 600 °C. */
        public final ConfigFloat heatingCoilMaxPower = f(1500, 1, 100000, "heatingCoilMaxPower",
                "Power in watts that holds the heating coil at 600 C");
        /** Thermal mass sets the rate of temperature rise for a given net power. */
        public final ConfigFloat heatingCoilMass = f(1, 0.01f, 100, "heatingCoilMass",
                "Thermal mass of the heating coil");
        /** Closed contacts retain a finite series resistance, in ohms. */
        public final ConfigFloat reversingSwitchResistance = f(0.001f, 0.0001f, 10, "reversingSwitchResistance",
                "Contact resistance of the reversing switch");
        /** Series resistance of the excitation winding, in ohms. */
        public final ConfigFloat excitationStatorResistance = f(100, 0.1f, 100000, "excitationStatorResistance",
                "Internal resistance of the excitation stator winding");
        public final ConfigFloat excitationStatorFieldFactor = f(2.1f, 0, 1000, "excitationStatorFieldFactor",
                "Multiplier for the field winding current contribution to rotor excitation");
        public final ConfigFloat excitationFieldPerStator = f(1000, 1, 100000, "excitationFieldPerStator",
                "Legacy setting. Native excitation uses the aligned stator count and field current.");
        public final ConfigFloat vanillaStatorField = f(100, 0, 10000, "vanillaStatorField",
                "Legacy setting. Each aligned permanent stator contributes one unit.");
        public final ConfigFloat hertzPerRpm = f(6.4f, 0.01f, 100, "hertzPerRpm",
                "Legacy setting. Native alternators have one pole pair and generate abs(rpm) / 60 Hz.");
        public final ConfigFloat frequencyDipMaxHz = f(0.08f, 0, 5, "frequencyDipMaxHz",
                "Upper bound on the frequency dip or lift caused by a load change");
        public final ConfigFloat frequencyDipFullScaleKilowatt = f(100, 1f, 100000, "frequencyDipFullScaleKilowatt",
                "Load step in kilowatts at which the frequency dip reaches frequencyDipMaxHz; changes below 5% of this value cause no frequency shift, between they scale linearly");
        public final ConfigInt frequencyDipDecayTicks = i(100, 1, 600, "frequencyDipDecayTicks",
                "Ticks over which a frequency dip decays back to the attainable frequency, 100 = 5 seconds");
        public final ConfigBool enableLightningStrikes = b(true, "enableLightningStrikes",
                "Whether thunderstorms strike the highest electric node with a surge current");
        /**
         * The lightning channel injects a current pulse through finite internal impedance.
         * Amplitude is in amperes.
         */
        public final ConfigFloat lightningImpulseCurrent = f(1000000, 1000, 10000000, "lightningImpulseCurrent",
                "Amplitude in amps of the Norton current source injected at the struck node, node voltage ceiling is about current * 0.1 ohm");
        public final ConfigInt lightningStrikeIntervalTicks = i(1200, 20, 72000, "lightningStrikeIntervalTicks",
                "Average ticks between lightning strikes per thundering level, 1200 = 60 seconds");
        /** Lightning source pulse width, in game ticks. */
        public final ConfigInt lightningStrikeDurationTicks = i(2, 1, 10, "lightningStrikeDurationTicks",
                "How many ticks the surge current source stays injected at the struck node");
        public final ConfigInt lightningContinuingCurrentTicks = i(60, 0, 1200, "lightningContinuingCurrentTicks",
                "Ticks the arc is sustained by the lightning continuing current after flashover, before the grid-fed criterion takes over; 0 = disabled");
        /** Flashover threshold gradient along the air gap, in volts per block. */
        public final ConfigFloat flashoverVoltsPerBlock = f(10000, 1000, 1000000, "flashoverVoltsPerBlock",
                "Flashover voltage increase per block of air gap between the struck node and the earth surface below");
        public final ConfigFloat flashoverFallbackVolts = f(60000, 1000, 10000000, "flashoverFallbackVolts",
                "Flashover threshold used when no discharge target can be found near the struck node. Must stay below the surge voltage ceiling (impulse current x 0.1 ohm)");
        public final ConfigFloat arcVoltsPerBlock = f(50, 1, 10000, "arcVoltsPerBlock",
                "Constant voltage drop per block of arc length; a source below this times the gap length cannot sustain the arc");
        public final ConfigInt dischargeSearchRadius = i(4, 1, 16, "dischargeSearchRadius",
                "Search radius in blocks for flashover targets (electric devices, solid blocks) and nearby players");
        public final ConfigInt arcDurationTicks = i(0, 0, 72000, "arcDurationTicks",
                "Max safety cap for the power-follow arc in ticks, 0 = unlimited (arc dies only by physics)");

        @Override
        public String getName() {
            return "server";
        }
    }
}
