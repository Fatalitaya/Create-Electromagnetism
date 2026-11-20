# Electrical device ratings and boiler load

This review uses the development configuration on 2026-11-19 and the requested boiler capacities of **147,456 SU for a level 9 boiler** and **294,912 SU for a full-capacity boiler**. The ordinary generator stator scales reasonably for large three-phase machines. The excitation stator is useful for voltage adjustment, but requires its entire continuous thermal allowance to match one ordinary stator. Among passive components, the largest discrepancy is the **1,300 W configured resistor rating versus a 380 W continuous thermal boundary**.

The figures below are calculations from the locked CEE dependency, Java adapters, Rust models and local configuration. No game instance was running during the investigations. Currents, temperatures, stress and protection behavior have not been measured in game. A thermal boundary is the operating point whose equilibrium temperature reaches the configured trip temperature. Continuous operation needs margin below it.

## Boiler capacity and generator load

The configured conversion is 1 N·m per unit of base stress. Create multiplies base stress by absolute shaft RPM. For steady generation, shaft power is torque times angular speed, so 1 kW corresponds to approximately **9,549 SU**. The following figures assume the full boiler capacity is available to the generator. Terminal output is lower than shaft input by machine losses. [Torque conversion](../src/main/java/com/electromagnetism/config/ModConfigs.java) · [Rotor load feedback](../src/main/java/com/electromagnetism/integration/cee/mixin/NativeRotorAccessMixin.java) · [Brush stress display](../src/main/java/com/electromagnetism/integration/cee/mixin/NativeBrushesAccessMixin.java)

| Boiler | Capacity | Shaft power | Available torque at 256 RPM |
|---|---:|---:|---:|
| Level 9 | 147,456 SU | 15.44 kW | 576 N·m |
| Full capacity | 294,912 SU | 30.88 kW | 1,152 N·m |

An effective ordinary stator contributes one unit to the assembly's permanent excitation. With the default motor constant of 1, its open-circuit contribution at 256 RPM is 26.81 V peak, or **18.96 V RMS per phase**. Both values halve at 128 RPM. Open-circuit electromagnetic torque is effectively zero. Under load the native solver calculates torque from phase current and rotor angle, and the bridge distributes total torque across the rotors. [Generator assembly and feedback](../src/main/java/com/electromagnetism/integration/cee/nativebridge/NativeAlternators.java) · [Native torque integration](../native/crates/em-core/src/solver/transient.rs)

Each assembly has one 1 Ω, 0.05 H armature winding per phase. Adding more rotors and stators increases induced voltage while retaining that per-phase winding model. At 100 K above ambient, copper resistance reaches 1.39 Ω. Cooling at 1 W/K then permits 100 W of copper loss, corresponding to **8.48 A RMS per phase**. The estimates below assume balanced three-phase operation at 256 RPM with current in phase with internal EMF. They are upper bounds on average shaft power at the winding thermal boundary.

| Effective ordinary stators in the assembly | Internal phase EMF, RMS | Shaft power | Level 9 boiler share | Full boiler share |
|---:|---:|---:|---:|---:|
| 4 | 75.8 V | 1.93 kW | 12.5% | 6.3% |
| 32 | 606.6 V | 15.43 kW | About 100% | About 50% |
| 64 | 1,213 V | 30.87 kW | Exceeds capacity | About 100% |

A four-stator rotor uses a modest share of either boiler. The 32- and 64-stator cases approach the respective boiler capacities with very little thermal margin. At the same stator count, phase current and speed, single-phase average power is about one third of the three-phase figure. Load phase angle, winding voltage drop and copper losses matter when sizing an actual machine.

## Excitation stator

The field winding has 100 Ω resistance, 1 H inductance and a zero resistance temperature coefficient. With the configured field factor of 1, **1 A DC equals one ordinary stator** in induced-voltage contribution. The following values assume steady DC. The SU column gives the ideal additional upstream cost if the same boiler supplies the field. An external battery bears that cost when it supplies the field. [Field winding](../src/main/java/com/electromagnetism/integration/cee/nativebridge/NativeMachineAdapter.java) · [Field coupling](../src/main/java/com/electromagnetism/integration/cee/nativebridge/NativeAlternators.java) · [Thermal model parameters](../src/main/java/com/electromagnetism/integration/cee/nativebridge/NativeProtection.java)

| Field supply | Field current | Continuous electrical demand | Ordinary stator equivalent | Ideal upstream cost |
|---:|---:|---:|---:|---:|
| 12 V | 0.12 A | 1.44 W | 0.12 | 14 SU |
| 48 V | 0.48 A | 23.0 W | 0.48 | 220 SU |
| 100 V | 1.00 A | 100 W | 1.00 | 955 SU |
| 220 V | 2.20 A | 484 W before protection | 2.20 before protection | 4,622 SU before protection |

At 100 V the winding uses its full 100 K temperature allowance. Sustained 220 V exceeds it. Matching one ordinary stator takes only 0.65% of the level 9 boiler or 0.32% of the full boiler, but occupies the same mounting position and requires continuous power. If 48 V is the intended working supply, raising `excitationStatorFieldFactor` to about **2.1** would give one ordinary stator equivalent at 23 W while preserving voltage control and thermal margin. The ordinary stator SU conversion can stay as configured.

The active bridge counts aligned ordinary stators directly. The configuration entries `vanillaStatorField` and `excitationFieldPerStator` do not enter this calculation. The generator uses one pole pair, giving **RPM / 60 Hz**, or 4.27 Hz at 256 RPM. The description attached to `hertzPerRpm` therefore also differs from the active path. [Configuration declarations](../src/main/java/com/electromagnetism/config/ModConfigs.java) · [Stator counting](../src/main/java/com/electromagnetism/integration/cee/mixin/NativeRotorAccessMixin.java) · [Native frequency calculation](../native/crates/em-core/src/session/circuit.rs)

## Connected loads and conversion equipment

| Device | Electrical assessment |
|---|---|
| Lamps and bell | The mod's 12 V / 4 W, 220 V / 11 W and 220 V / 110 W lamps give distinct low-voltage and mains-voltage loads. The 20 Ω bell draws 7.2 W at 12 V DC, within its 20 W thermal boundary. At 48 V it would draw 115.2 W. [Lamp ratings](../src/main/java/com/electromagnetism/registry/ModItems.java) · [Bell model](../src/main/java/com/electromagnetism/integration/cee/nativebridge/NativeLoadAdapter.java) |
| Heating coil | At 25 Ω the 1,500 W thermal boundary corresponds to about 194 V. A 220 V supply produces 1,936 W, about 29% over that boundary. If the coil should share the 220 V lighting bus at 1,500 W, the corresponding resistance is 32.3 Ω. [Heating coil model](../src/main/java/com/electromagnetism/integration/cee/nativebridge/NativeLoadAdapter.java) |
| Motor and pump | At 256 RPM the motor develops 26.8 V back EMF. A 48 V DC supply driving in the same direction leaves about 21.2 V across its 1 Ω winding, producing approximately 21.2 A and 449 W of copper loss against a 100 W thermal boundary. The pump has a rectified 48 V DC link. The two machines have different supply requirements. [Motor and pump models](../src/main/java/com/electromagnetism/integration/cee/nativebridge/NativeMachineAdapter.java) |
| Transformer | At 4.27 Hz the default 1 H magnetizing inductance draws approximately 8.2 A at 220 V RMS in the ideal magnetizing branch alone. This consumes almost all of a generator phase's 8.48 A thermal allowance before the transformer supplies a load. [Transformer configuration](../src/main/java/com/electromagnetism/config/ModConfigs.java) |
| Switches and FE conversion | Switch contacts are rated at 100 A. The FE converter has a 100 kW transfer limit and 90% efficiency. Either boiler reaches its shaft-power limit well below that converter limit. Winding current and available voltage can constrain a single generator earlier. The converter limit is more relevant to a shared bus with several generators. [Configuration](../src/main/java/com/electromagnetism/config/ModConfigs.java) · [Converter model](../src/main/java/com/electromagnetism/integration/cee/nativebridge/NativeStorageAdapter.java) |

## Capacitors, inductors and resistors

The locked CEE controls default to **300 µF, 300 µH and 1 kΩ**. Java passes the selected device values in farads, henries and ohms. Rust uses the standard stored energies ½CV² and ½LI². The examples below assume **220 V RMS sinusoidal voltage maintained at the component terminals and 4.27 Hz**. This assumption describes a comparison point. A real generator's terminal voltage can collapse under heavy load. [CEE dependency](../build.gradle) · [Storage adapter](../src/main/java/com/electromagnetism/integration/cee/nativebridge/NativeStorageAdapter.java) · [Rust element equations](../native/crates/em-core/src/model/element.rs)

| Setting | Result at the comparison point | Assessment |
|---|---|---|
| 300 µF capacitor | 124 Ω reactance, 1.77 A, 389 var and about 0.31 W series loss | A useful compensation range. Alone it draws about 21% of a phase's thermal current allowance. In parallel with a suitable inductive load it can reduce upstream current. |
| 300 µH inductor | 0.0080 Ω reactance, below its 0.1 Ω series resistance | Suitable for higher-frequency small-signal use. Across the low-frequency power bus it behaves close to a short circuit, with current limited mainly by the source and series resistances. |
| 1 H inductor | 26.8 Ω reactance and about 8.21 A | The upper adjustment range is useful for power circuits. This example approaches the generator phase's thermal boundary. |
| 1 kΩ resistor | 0.22 A and 48.4 W | A suitable light load. Changing to 100 Ω raises dissipation to 484 W, above the actual 380 W continuous boundary. |

The 48.4 W resistive load corresponds to about **462 SU** before upstream losses. The capacitor's 389 var describes energy exchanged each cycle. For boiler sizing, use real power and losses. For winding sizing, compare RMS current before and after adding compensation at the same real load.

### Capacitor voltage and inductor current limits

Ordinary and high-voltage capacitors share the 300 µF default. Their configured insulation voltages are **500 V** and **25 kV**. At those DC voltages they store **37.5 J** and **93.75 kJ** respectively. The latter equals about 6.1 seconds of ideal shaft output from the level 9 boiler. The high-voltage unit therefore has a distinct storage role despite using the same capacitance setting. [CEE voltage settings](../run/config/electroenergetics-server.toml) · [High-voltage and ordinary capacitor models](../src/main/java/com/electromagnetism/integration/cee/nativebridge/NativeStorageAdapter.java)

Both capacitor types and the ordinary inductor share **0.1 Ω series resistance** and **1 GΩ parallel insulation resistance**. The series element reaches its steady thermal boundary at approximately **61.6 A RMS**. The insulation has a roughly **one-second thermal time constant** and can tolerate short excursions above its configured voltage. The ordinary inductor also has a 500 V insulation setting. Component damage was enabled in the reviewed configuration. [Parasitic elements](../src/main/java/com/electromagnetism/integration/cee/nativebridge/NativeModelParameters.java) · [Protection selection](../src/main/java/com/electromagnetism/integration/cee/nativebridge/NativeWorldCapture.java)

Raising inductance from 300 µH to 1 H retains the same copper loss and current boundary. The inductor model has no magnetic saturation. This suits an adjustable idealized component and makes the highest settings generous for a fixed-size power choke. If the inductor is intended for the low-frequency power network by default, a **48 V, 4.27 Hz, 5 A** design point calls for about **0.36 H**, close to a 0.4 H setting. The 300 µH default remains useful for electronics.

### Resistor and potentiometer power ratings

CEE specifies 1,300 W for both devices. The native ordinary resistor instead uses **100 J/K heat capacity**, **1 W/K cooling**, **293.15 K ambient** and a **673.15 K trip temperature**. The resulting continuous thermal boundary is **380 W**. On a 220 V bus, resistance must exceed about **127 Ω** for steady thermal margin. The creative resistor uses a separate ideal resistor branch. [CEE settings](../run/config/electroenergetics-server.toml) · [Native thermal defaults](../src/main/java/com/electromagnetism/config/ModConfigs.java) · [Resistor and potentiometer models](../src/main/java/com/electromagnetism/integration/cee/nativebridge/NativeLoadAdapter.java)

The potentiometer divides the resistance by wiper position but assigns half the total cooling and heat capacity to each track segment at every position. Each segment can dissipate about **190 W** continuously at the thermal boundary. With the two ends connected and the wiper unloaded, the whole track reaches about **380 W at midpoint** and only **190 W near an end stop**, even though the end-to-end resistance is nearly constant. If 1,300 W is the intended rating, derive cooling from that setting and allocate track thermal parameters by active track length. If the 380 W balance is intentional, expose the effective rating to the player.

For scale, dissipating each boiler's full ideal shaft output on a 220 V resistor bank would require at least **41 or 82 ordinary resistors**, respectively, sized to share power evenly under the 380 W boundary. Using the displayed configuration value of 1,300 W gives **12 or 24**. These counts exclude generator losses and allow little operating margin. Aligning the effective and configured resistor ratings has the highest priority among passive-component changes, followed by choosing the inductor default for its intended use. The ordinary capacitor default can stay as configured.
