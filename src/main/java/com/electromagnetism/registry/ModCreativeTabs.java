package com.electromagnetism.registry;

import com.electromagnetism.ElectromagnetismMod;
import com.george_vi.electroenergetics.CEECreativeTab;
import com.george_vi.electroenergetics.CEEBlocks;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Define creative tab contents and their display order. */
public class ModCreativeTabs {
    private static final DeferredRegister<CreativeModeTab> REGISTER =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ElectromagnetismMod.ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = REGISTER.register("base",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.electromagnetism"))
                    .withTabsBefore(CEECreativeTab.CREATIVE_TAB.getId())
                    .icon(ModBlocks.LIGHT_FIXTURE::asStack)
                    .displayItems((parameters, output) -> {
                        output.accept(ModBlocks.MICROPHONE.asStack());
                        output.accept(ModBlocks.ANTENNA.asStack());
                        output.accept(ModBlocks.YAGI_ANTENNA.asStack());
                        output.accept(ModBlocks.OSCILLOSCOPE.asStack());
                        output.accept(ModBlocks.SIGNAL_GENERATOR.asStack());
                        output.accept(ModBlocks.RADIO_TRANSMITTER.asStack());
                        output.accept(ModBlocks.RADIO_RECEIVER.asStack());
                        output.accept(ModBlocks.LIGHT_FIXTURE.asStack());
                        output.accept(ModItems.LV_LIGHT_BULB.asStack());
                        output.accept(ModItems.LIGHT_BULB.asStack());
                        output.accept(ModItems.GROWTH_LAMP.asStack());
                        output.accept(ModBlocks.FACTORY_LIGHT.asStack());
                        output.accept(ModBlocks.CONDUCTIVE_CASING.asStack());
                        output.accept(ModBlocks.HEATING_COIL.asStack());
                        output.accept(ModBlocks.REVERSING_SWITCH.asStack());
                        output.accept(ModBlocks.EXCITATION_STATOR.asStack());
                        output.accept(ModBlocks.AC_MOTOR.asStack());
                        output.accept(ModBlocks.THREE_PHASE_MOTOR.asStack());
                        output.accept(ModBlocks.HIGH_VOLTAGE_RECTIFIER.asStack());
                        output.accept(ModBlocks.ZENER_DIODE.asStack());
                        output.accept(ModBlocks.POWER_CHOKE.asStack());
                        output.accept(ModBlocks.NPN_TRANSISTOR.asStack());
                        output.accept(ModBlocks.PNP_TRANSISTOR.asStack());
                        output.accept(ModBlocks.N_MOSFET.asStack());
                        output.accept(ModBlocks.P_MOSFET.asStack());
                        output.accept(ModBlocks.ELECTRIC_BELL.asStack());
                        output.accept(ModBlocks.ICE_BELL.asStack());
                        output.accept(ModBlocks.NAILONG_BELL.asStack());
                        output.accept(CEEBlocks.THREE_PHASE_ALTERNATOR_BRUSHES.asStack());
                        output.accept(CEEBlocks.FREQUENCY_METER.asStack());
                        output.accept(CEEBlocks.SYNCHROSCOPE.asStack());
                    })
                    .build());

    public static void register(IEventBus bus) {
        REGISTER.register(bus);
    }
}
