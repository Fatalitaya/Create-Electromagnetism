package com.electromagnetism.registry;

import com.electromagnetism.ElectromagnetismMod;
import com.electromagnetism.client.model.AcMotorRenderer;
import com.george_vi.electroenergetics.CEEPartialModels;
import com.electromagnetism.content.electromechanics.AcMotorBlockEntity;
import com.simibubi.create.content.kinetics.base.OrientedRotatingVisual;

import com.electromagnetism.content.electromechanics.excitation.ExcitationStatorBlockEntity;
import com.electromagnetism.content.lighting.factory.FactoryLightBlockEntity;
import com.electromagnetism.content.lighting.factory.FactoryLightLightBlockEntity;
import com.electromagnetism.content.lighting.factory.FactoryLightRenderer;
import com.electromagnetism.content.lighting.fixture.LightFixtureBlockEntity;
import com.electromagnetism.content.lighting.fixture.LightFixtureRenderer;
import com.electromagnetism.content.thermal.heater.HeatingCoilBlockEntity;
import com.tterrag.registrate.util.entry.BlockEntityEntry;

import static com.electromagnetism.ElectromagnetismMod.REGISTRATE;

/**
 * Bind block entity types to the blocks that share them and register renderers for dynamic
 * displays.
 */
public class ModBlockEntityTypes {
    public static final BlockEntityEntry<AcMotorBlockEntity> AC_MOTOR =
            REGISTRATE.blockEntity("ac_motor", AcMotorBlockEntity::new)
                    .visual(() -> OrientedRotatingVisual.of(CEEPartialModels.ELECTRIC_MOTOR_SHAFT), false)
                    .validBlock(ModBlocks.AC_MOTOR)
                    .validBlock(ModBlocks.THREE_PHASE_MOTOR)
                    .renderer(() -> AcMotorRenderer::new)
                    .register();

    public static final BlockEntityEntry<LightFixtureBlockEntity> LIGHT_FIXTURE =
            REGISTRATE.blockEntity("light_fixture", LightFixtureBlockEntity::new)
                    .validBlock(ModBlocks.LIGHT_FIXTURE)
                    .renderer(() -> LightFixtureRenderer::new)
                    .register();

    public static final BlockEntityEntry<FactoryLightBlockEntity> FACTORY_LIGHT =
            REGISTRATE.blockEntity("factory_light", FactoryLightBlockEntity::new)
                    .validBlock(ModBlocks.FACTORY_LIGHT)
                    .renderer(() -> FactoryLightRenderer::new)
                    .register();

    public static final BlockEntityEntry<FactoryLightLightBlockEntity> FACTORY_LIGHT_LIGHT =
            REGISTRATE.blockEntity("factory_light_light", FactoryLightLightBlockEntity::new)
                    .validBlock(ModBlocks.FACTORY_LIGHT_LIGHT)
                    .register();


    public static final BlockEntityEntry<HeatingCoilBlockEntity> HEATING_COIL =
            REGISTRATE.blockEntity("heating_coil", HeatingCoilBlockEntity::new)
                    .validBlock(ModBlocks.HEATING_COIL)
                    .register();

    public static final BlockEntityEntry<ExcitationStatorBlockEntity> EXCITATION_STATOR =
            REGISTRATE.blockEntity("excitation_stator", ExcitationStatorBlockEntity::new)
                    .validBlock(ModBlocks.EXCITATION_STATOR)
                    .register();

    /** NPN and PNP blocks share a block entity. The placed block determines polarity. */
    public static final BlockEntityEntry<com.electromagnetism.content.electronics.bjt.TransistorBlockEntity> TRANSISTOR =
            REGISTRATE.blockEntity("transistor", com.electromagnetism.content.electronics.bjt.TransistorBlockEntity::new)
                    .validBlock(ModBlocks.NPN_TRANSISTOR)
                    .validBlock(ModBlocks.PNP_TRANSISTOR)
                    .register();

    /** Both channel types share the editor. The device instance stores channel polarity. */
    public static final BlockEntityEntry<com.electromagnetism.content.electronics.mosfet.MosfetBlockEntity> MOSFET =
            REGISTRATE.blockEntity("mosfet", com.electromagnetism.content.electronics.mosfet.MosfetBlockEntity::new)
                    .validBlock(ModBlocks.N_MOSFET)
                    .validBlock(ModBlocks.P_MOSFET)
                    .register();

    public static final BlockEntityEntry<com.electromagnetism.content.acoustics.bell.ElectricBellBlockEntity> ELECTRIC_BELL =
            REGISTRATE.blockEntity("electric_bell", com.electromagnetism.content.acoustics.bell.ElectricBellBlockEntity::new)
                    .validBlock(ModBlocks.ELECTRIC_BELL)
                    .validBlock(ModBlocks.ICE_BELL)
                    .validBlock(ModBlocks.NAILONG_BELL)
                    .register();

    /** Class initialization runs the Registrate declarations. This method triggers loading. */
    public static final BlockEntityEntry<com.electromagnetism.content.instruments.InstrumentBlockEntity> INSTRUMENT = REGISTRATE.blockEntity("instrument", com.electromagnetism.content.instruments.InstrumentBlockEntity::new).validBlock(ModBlocks.ANTENNA).validBlock(ModBlocks.YAGI_ANTENNA).validBlock(ModBlocks.MICROPHONE).validBlock(ModBlocks.OSCILLOSCOPE).validBlock(ModBlocks.RADIO_TRANSMITTER).validBlock(ModBlocks.RADIO_RECEIVER).register();
    public static final BlockEntityEntry<com.electromagnetism.content.instruments.SignalGeneratorBlockEntity> SIGNAL_GENERATOR = REGISTRATE.blockEntity("signal_generator", com.electromagnetism.content.instruments.SignalGeneratorBlockEntity::new).validBlock(ModBlocks.SIGNAL_GENERATOR).register();
    public static void register() {
    }
}
