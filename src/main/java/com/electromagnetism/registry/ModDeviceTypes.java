package com.electromagnetism.registry;

import com.electromagnetism.ElectromagnetismMod;
import com.electromagnetism.content.electronics.PowerChokeDevice;
import com.george_vi.electroenergetics.content.electronic_components.diode.DiodeDevice;

import com.electromagnetism.content.electromechanics.excitation.ExcitationStatorDevice;
import com.electromagnetism.content.lighting.factory.FactoryLightDevice;
import com.electromagnetism.content.lighting.fixture.LightFixtureDevice;
import com.electromagnetism.content.thermal.heater.HeatingCoilDevice;
import com.electromagnetism.content.electrical.switching.ReversingSwitchDevice;
import com.george_vi.electroenergetics.CEERegistries;
import com.george_vi.electroenergetics.devices.device.SimulatedDeviceType;
import com.george_vi.electroenergetics.foundation.device.SimpleNonTickingElectricalDevice;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Register factories for CEE world devices. Their instances and parameters persist with the world. */
public class ModDeviceTypes {
    private static final DeferredRegister<SimulatedDeviceType<?>> DEVICES =
            DeferredRegister.create(CEERegistries.SIMULATED_DEVICE_TYPE, ElectromagnetismMod.ID);

    public static final DeferredHolder<SimulatedDeviceType<?>, SimulatedDeviceType<SimpleNonTickingElectricalDevice>> RAIL_TERMINAL =
            DEVICES.register("rail_terminal", () -> new SimulatedDeviceType<>(ElectromagnetismMod.rl("rail_terminal"),
                    (type, level, pos, data) -> new SimpleNonTickingElectricalDevice(level, pos, data, type)));

    public static final DeferredHolder<SimulatedDeviceType<?>, SimulatedDeviceType<SimpleNonTickingElectricalDevice>> BOGEY_TERMINAL =
            DEVICES.register("bogey_terminal", () -> new SimulatedDeviceType<>(ElectromagnetismMod.rl("bogey_terminal"),
                    (type, level, pos, data) -> new SimpleNonTickingElectricalDevice(level, pos, data, type)));

    public static final DeferredHolder<SimulatedDeviceType<?>, SimulatedDeviceType<SimpleNonTickingElectricalDevice>> AC_MOTOR_TERMINALS =
            DEVICES.register("ac_motor_terminals", () -> new SimulatedDeviceType<>(ElectromagnetismMod.rl("ac_motor_terminals"),
                    (type, level, pos, data) -> new SimpleNonTickingElectricalDevice(level, pos, data, type) {
                        @Override
                        public boolean shouldRemove(net.minecraft.world.level.block.state.BlockState oldState,
                                                    net.minecraft.world.level.block.state.BlockState newState) {
                            return oldState.getBlock() != newState.getBlock();
                        }
                    }));

    private static DeferredHolder<SimulatedDeviceType<?>, SimulatedDeviceType<DiodeDevice>> rectifier(String name) {
        return DEVICES.register(name, () -> new SimulatedDeviceType<>(ElectromagnetismMod.rl(name),
                (type, level, pos, data) -> new DiodeDevice(level, pos, data, type)));
    }

    public static final DeferredHolder<SimulatedDeviceType<?>, SimulatedDeviceType<DiodeDevice>> HIGH_VOLTAGE_RECTIFIER = rectifier("high_voltage_rectifier");
    public static final DeferredHolder<SimulatedDeviceType<?>, SimulatedDeviceType<DiodeDevice>> ZENER_DIODE = rectifier("zener_diode");
    public static final DeferredHolder<SimulatedDeviceType<?>, SimulatedDeviceType<PowerChokeDevice>> POWER_CHOKE =
            DEVICES.register("power_choke", () -> new SimulatedDeviceType<>(ElectromagnetismMod.rl("power_choke"),
                    (type, level, pos, data) -> new PowerChokeDevice(level, pos, data, type)));

    public static final DeferredHolder<SimulatedDeviceType<?>, SimulatedDeviceType<LightFixtureDevice>> LIGHT_FIXTURE =
            DEVICES.register("light_fixture", () -> new SimulatedDeviceType<>(
                    ElectromagnetismMod.rl("light_fixture"),
                    (type, level, pos, sd) -> new LightFixtureDevice(level, pos, sd, type)));

    public static final DeferredHolder<SimulatedDeviceType<?>, SimulatedDeviceType<FactoryLightDevice>> FACTORY_LIGHT =
            DEVICES.register("factory_light", () -> new SimulatedDeviceType<>(
                    ElectromagnetismMod.rl("factory_light"),
                    (type, level, pos, sd) -> new FactoryLightDevice(level, pos, sd, type)));


    public static final DeferredHolder<SimulatedDeviceType<?>, SimulatedDeviceType<HeatingCoilDevice>> HEATING_COIL =
            DEVICES.register("heating_coil", () -> new SimulatedDeviceType<>(
                    ElectromagnetismMod.rl("heating_coil"),
                    (type, level, pos, sd) -> new HeatingCoilDevice(level, pos, sd, type)));

    public static final DeferredHolder<SimulatedDeviceType<?>, SimulatedDeviceType<ReversingSwitchDevice>> REVERSING_SWITCH =
            DEVICES.register("reversing_switch", () -> new SimulatedDeviceType<>(
                    ElectromagnetismMod.rl("reversing_switch"),
                    (type, level, pos, sd) -> new ReversingSwitchDevice(level, pos, sd, type)));

    public static final DeferredHolder<SimulatedDeviceType<?>, SimulatedDeviceType<ExcitationStatorDevice>> EXCITATION_STATOR =
            DEVICES.register("excitation_stator", () -> new SimulatedDeviceType<>(
                    ElectromagnetismMod.rl("excitation_stator"),
                    (type, level, pos, sd) -> new ExcitationStatorDevice(level, pos, sd, type)));

    public static final DeferredHolder<SimulatedDeviceType<?>, SimulatedDeviceType<com.electromagnetism.content.electronics.bjt.TransistorDevice>> NPN_TRANSISTOR =
            DEVICES.register("npn_transistor", () -> new SimulatedDeviceType<>(
                    ElectromagnetismMod.rl("npn_transistor"),
                    (type, level, pos, sd) -> new com.electromagnetism.content.electronics.bjt.TransistorDevice(level, pos, sd, type)));

    /** The PNP factory sets polarity on the shared device implementation. */
    public static final DeferredHolder<SimulatedDeviceType<?>, SimulatedDeviceType<com.electromagnetism.content.electronics.bjt.TransistorDevice>> PNP_TRANSISTOR =
            DEVICES.register("pnp_transistor", () -> new SimulatedDeviceType<>(
                    ElectromagnetismMod.rl("pnp_transistor"),
                    (type, level, pos, sd) -> {
                        com.electromagnetism.content.electronics.bjt.TransistorDevice device =
                                new com.electromagnetism.content.electronics.bjt.TransistorDevice(level, pos, sd, type);
                        device.pnp = true;
                        return device;
                    }));

    public static final DeferredHolder<SimulatedDeviceType<?>, SimulatedDeviceType<com.electromagnetism.content.electronics.mosfet.MosfetDevice>> N_MOSFET =
            DEVICES.register("n_mosfet", () -> new SimulatedDeviceType<>(
                    ElectromagnetismMod.rl("n_mosfet"),
                    (type, level, pos, sd) -> new com.electromagnetism.content.electronics.mosfet.MosfetDevice(level, pos, sd, type)));

    /** The P-channel factory sets the channel flag explicitly. */
    public static final DeferredHolder<SimulatedDeviceType<?>, SimulatedDeviceType<com.electromagnetism.content.electronics.mosfet.MosfetDevice>> P_MOSFET =
            DEVICES.register("p_mosfet", () -> new SimulatedDeviceType<>(
                    ElectromagnetismMod.rl("p_mosfet"),
                    (type, level, pos, sd) -> {
                        com.electromagnetism.content.electronics.mosfet.MosfetDevice device =
                                new com.electromagnetism.content.electronics.mosfet.MosfetDevice(level, pos, sd, type);
                        device.pChannel = true;
                        return device;
                    }));

    public static final DeferredHolder<SimulatedDeviceType<?>, SimulatedDeviceType<com.electromagnetism.content.acoustics.bell.ElectricBellDevice>> ELECTRIC_BELL =
            DEVICES.register("electric_bell", () -> new SimulatedDeviceType<>(
                    ElectromagnetismMod.rl("electric_bell"),
                    (type, level, pos, sd) -> new com.electromagnetism.content.acoustics.bell.ElectricBellDevice(level, pos, sd, type)));

    public static final DeferredHolder<SimulatedDeviceType<?>, SimulatedDeviceType<com.electromagnetism.content.instruments.MicrophoneDevice>> MICROPHONE = DEVICES.register("microphone", () -> new SimulatedDeviceType<>(ElectromagnetismMod.rl("microphone"), (type, level, pos, sd) -> new com.electromagnetism.content.instruments.MicrophoneDevice(level, pos, sd, type)));
    public static final DeferredHolder<SimulatedDeviceType<?>, SimulatedDeviceType<com.electromagnetism.content.instruments.OscilloscopeDevice>> OSCILLOSCOPE = DEVICES.register("oscilloscope", () -> new SimulatedDeviceType<>(ElectromagnetismMod.rl("oscilloscope"), (type, level, pos, sd) -> new com.electromagnetism.content.instruments.OscilloscopeDevice(level, pos, sd, type)));
    public static final DeferredHolder<SimulatedDeviceType<?>, SimulatedDeviceType<com.electromagnetism.content.instruments.RadioDevice>> RADIO_TRANSMITTER = DEVICES.register("radio_transmitter", () -> new SimulatedDeviceType<>(ElectromagnetismMod.rl("radio_transmitter"), (type, level, pos, sd) -> { var d=new com.electromagnetism.content.instruments.RadioDevice(level,pos,sd,type);d.transmitter=true;return d; }));
    public static final DeferredHolder<SimulatedDeviceType<?>, SimulatedDeviceType<com.electromagnetism.content.instruments.RadioDevice>> RADIO_RECEIVER = DEVICES.register("radio_receiver", () -> new SimulatedDeviceType<>(ElectromagnetismMod.rl("radio_receiver"), (type, level, pos, sd) -> new com.electromagnetism.content.instruments.RadioDevice(level,pos,sd,type)));
    public static final DeferredHolder<SimulatedDeviceType<?>, SimulatedDeviceType<com.electromagnetism.content.instruments.SignalGeneratorDevice>> SIGNAL_GENERATOR = DEVICES.register("signal_generator", () -> new SimulatedDeviceType<>(ElectromagnetismMod.rl("signal_generator"), (type, level, pos, sd) -> new com.electromagnetism.content.instruments.SignalGeneratorDevice(level,pos,sd,type)));
    public static final DeferredHolder<SimulatedDeviceType<?>, SimulatedDeviceType<com.electromagnetism.content.instruments.AntennaDevice>> ANTENNA =
            DEVICES.register("antenna", () -> new SimulatedDeviceType<>(ElectromagnetismMod.rl("antenna"),
                    (type, level, pos, sd) -> new com.electromagnetism.content.instruments.AntennaDevice(level,pos,sd,type)));

    public static void register(IEventBus bus) {
        DEVICES.register(bus);
    }
}
