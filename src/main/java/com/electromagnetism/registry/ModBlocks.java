package com.electromagnetism.registry;

import com.electromagnetism.content.electronics.PowerChokeBlock;
import com.electromagnetism.content.electronics.RatedDiodeBlock;

import com.electromagnetism.ElectromagnetismMod;
import com.electromagnetism.client.model.ModPartialModels;

import com.electromagnetism.content.electromechanics.excitation.ExcitationStatorBlock;
import com.electromagnetism.content.electromechanics.AcMotorBlock;
import com.electromagnetism.content.lighting.factory.FactoryLightBlock;
import com.electromagnetism.content.lighting.factory.FactoryLightLightBlock;
import com.electromagnetism.content.lighting.fixture.LightFixtureBlock;
import com.electromagnetism.content.thermal.heater.HeatingCoilBlock;
import com.electromagnetism.content.electrical.switching.ReversingSwitchBlock;
import com.george_vi.electroenergetics.CEETags;
import com.george_vi.electroenergetics.foundation.base.DirectionalRolledDeviceBlock;
import com.simibubi.create.AllTags;
import com.simibubi.create.content.decoration.encasing.CasingBlock;
import com.simibubi.create.content.decoration.encasing.EncasedCTBehaviour;
import com.simibubi.create.foundation.data.SharedProperties;
import com.tterrag.registrate.util.entry.BlockEntry;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.client.model.generators.ConfiguredModel;

import static com.electromagnetism.ElectromagnetismMod.REGISTRATE;
import static com.simibubi.create.foundation.data.CreateRegistrate.casingConnectivity;
import static com.simibubi.create.foundation.data.CreateRegistrate.connectedTextures;
import static com.simibubi.create.foundation.data.TagGen.pickaxeOnly;

/** Register blocks, state models and associated items under the same namespace. */
public class ModBlocks {
    static {
        // The custom creative tab determines item display order.
        REGISTRATE.defaultCreativeTab((net.minecraft.resources.ResourceKey<net.minecraft.world.item.CreativeModeTab>) null);
    }

    public static final BlockEntry<RatedDiodeBlock> HIGH_VOLTAGE_RECTIFIER = REGISTRATE
            .block("high_voltage_rectifier", p -> new RatedDiodeBlock(p, ModDeviceTypes.HIGH_VOLTAGE_RECTIFIER))
            .initialProperties(SharedProperties::stone).properties(p -> p.noOcclusion())
            .transform(pickaxeOnly()).simpleItem().register();

    public static final BlockEntry<RatedDiodeBlock> ZENER_DIODE = REGISTRATE
            .block("zener_diode", p -> new RatedDiodeBlock(p, ModDeviceTypes.ZENER_DIODE))
            .initialProperties(SharedProperties::stone).properties(p -> p.noOcclusion())
            .transform(pickaxeOnly()).simpleItem().register();

    public static final BlockEntry<PowerChokeBlock> POWER_CHOKE = REGISTRATE
            .block("power_choke", PowerChokeBlock::new)
            .initialProperties(SharedProperties::stone).properties(p -> p.noOcclusion())
            .transform(pickaxeOnly()).simpleItem().register();

    public static final BlockEntry<LightFixtureBlock> LIGHT_FIXTURE = REGISTRATE.block("light_fixture", LightFixtureBlock::new)
            .tag(CEETags.LIGHT, ModTags.SABLE_LIGHT)
            .initialProperties(SharedProperties::softMetal)
            .properties(p -> p.mapColor(MapColor.COLOR_GRAY).noOcclusion()
                    .lightLevel(s -> switch (s.getValue(LightFixtureBlock.POWER)) {
                        case 1 -> 10;
                        case 2 -> 15;
                        default -> 0;
                    }))
            // Transform wall and ceiling models according to FACING and ROLL.
            .blockstate((c, p) -> p.getVariantBuilder(c.getEntry()).forAllStates(state -> {
                Direction facing = state.getValue(LightFixtureBlock.FACING);
                boolean roll = state.getValue(DirectionalRolledDeviceBlock.ROLL);
                boolean vertical = facing.getAxis().isVertical();
                int x = 0, y = 0;
                switch (facing) {
                    case UP -> {
                    }
                    case DOWN -> x = 180;
                    case EAST -> y = 180;
                    case NORTH -> y = 90;
                    case SOUTH -> y = -90;
                    default -> {
                    }
                }
                if (roll && vertical)
                    y = 90;
                else if (roll && !vertical)
                    x = -90;
                String suffix = vertical ? "_v" : "_h";
                return ConfiguredModel.builder()
                        .modelFile(p.models().getExistingFile(p.modLoc("block/fixtures/light_fixture" + suffix)))
                        .rotationX(x)
                        .rotationY(y)
                        .build();
            }))
            .transform(pickaxeOnly())
            .item()
            .model((c, p) -> p.withExistingParent(c.getName(), p.modLoc("block/fixtures/light_fixture_v")))
            .build()
            .register();

    public static final BlockEntry<FactoryLightBlock> FACTORY_LIGHT = REGISTRATE.block("factory_light", FactoryLightBlock::new)
            .tag(CEETags.LIGHT, ModTags.SABLE_LIGHT)
            .initialProperties(SharedProperties::softMetal)
            .properties(p -> p.mapColor(MapColor.COLOR_GRAY).noOcclusion()
                    .lightLevel(s -> switch (s.getValue(FactoryLightBlock.POWER)) {
                        case 2 -> 10;
                        case 3 -> 15;
                        default -> 0;
                    }))
            .transform(pickaxeOnly())
            .item()
            .model((c, p) -> p.withExistingParent(c.getName(), p.modLoc("block/factory_light/factorylight")))
            .build()
            .register();

    /** Only fixtures place projected light blocks, which provide world illumination. */
    public static final BlockEntry<FactoryLightLightBlock> FACTORY_LIGHT_LIGHT = REGISTRATE.block("factory_light_light", FactoryLightLightBlock::new)
            .properties(p -> p.lightLevel(s -> s.getValue(FactoryLightLightBlock.POWER) == 1 ? 15 : 10))
            .register();

    public static final BlockEntry<CasingBlock> CONDUCTIVE_CASING = REGISTRATE.block("conductive_casing", CasingBlock::new)
            .initialProperties(SharedProperties::softMetal)
            .properties(p -> p.mapColor(MapColor.METAL))
            .onRegister(connectedTextures(() -> new EncasedCTBehaviour(ModPartialModels.CONDUCTIVE_CASING)))
            .onRegister(casingConnectivity((block, cc) -> cc.makeCasing(block, ModPartialModels.CONDUCTIVE_CASING)))
            .transform(pickaxeOnly())
            .simpleItem()
            .register();


    public static final BlockEntry<HeatingCoilBlock> HEATING_COIL = REGISTRATE.block("heating_coil", HeatingCoilBlock::new)
            .initialProperties(SharedProperties::softMetal)
            .properties(p -> p.mapColor(MapColor.COLOR_GRAY).noOcclusion())
            .tag(AllTags.AllBlockTags.FAN_TRANSPARENT.tag)
            .transform(pickaxeOnly())
            .item()
            .model((c, p) -> p.withExistingParent(c.getName(), p.modLoc("block/heating_coil")))
            .build()
            .register();

    public static final BlockEntry<ReversingSwitchBlock> REVERSING_SWITCH = REGISTRATE.block("reversing_switch", ReversingSwitchBlock::new)
            .tag(CEETags.LIGHT)
            .initialProperties(SharedProperties::stone)
            .properties(p -> p.mapColor(MapColor.TERRACOTTA_WHITE).noOcclusion())
            .blockstate((c, p) -> DirectionalRolledDeviceBlock.generateBlockState(c, p,
                    bs -> ResourceLocation.fromNamespaceAndPath("electroenergetics",
                            bs.getValue(com.george_vi.electroenergetics.content.cut_off_switch.CutOffSwitchBlock.CLOSED)
                                    ? "block/double_switch/block_closed"
                                    : "block/double_switch/block")))
            .transform(pickaxeOnly())
            .item()
            .model((c, p) -> p.withExistingParent(c.getName(),
                    ResourceLocation.fromNamespaceAndPath("electroenergetics", "block/double_switch/block")))
            .build()
            .register();

    public static final BlockEntry<com.electromagnetism.content.electronics.bjt.TransistorBlock> NPN_TRANSISTOR = REGISTRATE.block("npn_transistor", com.electromagnetism.content.electronics.bjt.TransistorBlock::new)
            .initialProperties(SharedProperties::stone)
            .properties(p -> p.mapColor(MapColor.COLOR_GRAY).noOcclusion())
            .transform(pickaxeOnly())
            .item()
            .model((c, p) -> p.withExistingParent(c.getName(), p.modLoc("block/transistor")))
            .build()
            .register();

    public static final BlockEntry<com.electromagnetism.content.electronics.bjt.PnpTransistorBlock> PNP_TRANSISTOR = REGISTRATE.block("pnp_transistor", com.electromagnetism.content.electronics.bjt.PnpTransistorBlock::new)
            .initialProperties(SharedProperties::stone)
            .properties(p -> p.mapColor(MapColor.COLOR_ORANGE).noOcclusion())
            .transform(pickaxeOnly())
            .item()
            .model((c, p) -> p.withExistingParent(c.getName(), p.modLoc("block/transistor")))
            .build()
            .register();

    public static final BlockEntry<com.electromagnetism.content.electronics.mosfet.MosfetBlock> N_MOSFET = REGISTRATE.block("n_mosfet", com.electromagnetism.content.electronics.mosfet.MosfetBlock::new)
            .initialProperties(SharedProperties::stone)
            .properties(p -> p.mapColor(MapColor.COLOR_LIGHT_GRAY).noOcclusion())
            .transform(pickaxeOnly())
            .item()
            .model((c, p) -> p.withExistingParent(c.getName(), p.modLoc("block/mosfet")))
            .build()
            .register();

    public static final BlockEntry<com.electromagnetism.content.electronics.mosfet.PMosfetBlock> P_MOSFET = REGISTRATE.block("p_mosfet", com.electromagnetism.content.electronics.mosfet.PMosfetBlock::new)
            .initialProperties(SharedProperties::stone)
            .properties(p -> p.mapColor(MapColor.COLOR_CYAN).noOcclusion())
            .transform(pickaxeOnly())
            .item()
            .model((c, p) -> p.withExistingParent(c.getName(), p.modLoc("block/mosfet")))
            .build()
            .register();

    public static final BlockEntry<com.electromagnetism.content.acoustics.bell.ElectricBellBlock> ELECTRIC_BELL = REGISTRATE.block("electric_bell", com.electromagnetism.content.acoustics.bell.ElectricBellBlock::new)
            .initialProperties(SharedProperties::stone)
            .properties(p -> p.mapColor(MapColor.COLOR_LIGHT_GRAY).noOcclusion())
            .transform(pickaxeOnly())
            .item()
            .model((c, p) -> p.withExistingParent(c.getName(), p.modLoc("block/electric_bell")))
            .build()
            .register();

    public static final BlockEntry<com.electromagnetism.content.acoustics.bell.IceBellBlock> ICE_BELL = REGISTRATE.block("ice_bell", com.electromagnetism.content.acoustics.bell.IceBellBlock::new)
            .initialProperties(SharedProperties::stone)
            .properties(p -> p.mapColor(MapColor.COLOR_LIGHT_BLUE).noOcclusion())
            .transform(pickaxeOnly())
            .item()
            .model((c, p) -> p.withExistingParent(c.getName(), p.modLoc("block/electric_bell")))
            .build()
            .register();

    public static final BlockEntry<com.electromagnetism.content.acoustics.bell.NailongBellBlock> NAILONG_BELL = REGISTRATE.block("nailong_bell", com.electromagnetism.content.acoustics.bell.NailongBellBlock::new)
            .initialProperties(SharedProperties::stone)
            .properties(p -> p.mapColor(MapColor.COLOR_YELLOW).noOcclusion())
            .transform(pickaxeOnly())
            .item()
            .model((c, p) -> p.withExistingParent(c.getName(), p.modLoc("block/nailong_bell")))
            .build()
            .register();

    public static final BlockEntry<AcMotorBlock> AC_MOTOR = registerAcMotor("ac_motor", false);
    public static final BlockEntry<AcMotorBlock> THREE_PHASE_MOTOR = registerAcMotor("three_phase_motor", true);

    private static BlockEntry<AcMotorBlock> registerAcMotor(String name, boolean threePhase) {
        var blockModel = threePhase ? ElectromagnetismMod.rl("block/three_phase_motor")
                : ResourceLocation.fromNamespaceAndPath("electroenergetics", "block/red_electric_motor");
        var itemModel = threePhase ? ElectromagnetismMod.rl("block/three_phase_motor_item")
                : ResourceLocation.fromNamespaceAndPath("electroenergetics", "item/red_electric_motor");
        return REGISTRATE.block(name, properties -> new AcMotorBlock(properties, threePhase))
                .initialProperties(SharedProperties::softMetal)
                .properties(p -> p.mapColor(MapColor.COLOR_RED).noOcclusion())
                .blockstate((c, p) -> p.directionalBlock(c.getEntry(), p.models().getExistingFile(blockModel)))
                .transform(pickaxeOnly())
                .item()
                .model((c, p) -> p.withExistingParent(c.getName(), itemModel))
                .build()
                .register();
    }

    public static final BlockEntry<ExcitationStatorBlock> EXCITATION_STATOR = REGISTRATE.block("excitation_stator", ExcitationStatorBlock::new)
            .initialProperties(SharedProperties::stone)
            .properties(p -> p.mapColor(MapColor.COLOR_GRAY))
            .transform(pickaxeOnly())
            .item()
            .model((c, p) -> p.withExistingParent(c.getName(), p.modLoc("block/excitation_stator/block")))
            .build()
            .register();

    public static final BlockEntry<com.electromagnetism.content.instruments.AntennaBlock> ANTENNA = REGISTRATE.block("antenna", com.electromagnetism.content.instruments.AntennaBlock::new).initialProperties(SharedProperties::softMetal).properties(p -> p.noOcclusion()).transform(pickaxeOnly()).simpleItem().register();
    public static final BlockEntry<com.electromagnetism.content.instruments.MicrophoneBlock> MICROPHONE = REGISTRATE.block("microphone", com.electromagnetism.content.instruments.MicrophoneBlock::new).initialProperties(SharedProperties::stone).properties(p -> p.noOcclusion()).transform(pickaxeOnly()).simpleItem().register();
    public static final BlockEntry<com.electromagnetism.content.instruments.SignalGeneratorBlock> SIGNAL_GENERATOR = REGISTRATE.block("signal_generator", com.electromagnetism.content.instruments.SignalGeneratorBlock::new).initialProperties(SharedProperties::stone).properties(p -> p.noOcclusion()).transform(pickaxeOnly()).simpleItem().register();
    public static final BlockEntry<com.electromagnetism.content.instruments.OscilloscopeBlock> OSCILLOSCOPE = REGISTRATE.block("oscilloscope", com.electromagnetism.content.instruments.OscilloscopeBlock::new).initialProperties(SharedProperties::stone).properties(p -> p.noOcclusion()).transform(pickaxeOnly()).simpleItem().register();
    public static final BlockEntry<com.electromagnetism.content.instruments.RadioBlock> RADIO_TRANSMITTER = REGISTRATE.block("radio_transmitter", p->new com.electromagnetism.content.instruments.RadioBlock(p,true)).initialProperties(SharedProperties::softMetal).properties(p->p.noOcclusion()).transform(pickaxeOnly()).simpleItem().register();
    public static final BlockEntry<com.electromagnetism.content.instruments.RadioBlock> RADIO_RECEIVER = REGISTRATE.block("radio_receiver", p->new com.electromagnetism.content.instruments.RadioBlock(p,false)).initialProperties(SharedProperties::softMetal).properties(p->p.noOcclusion()).transform(pickaxeOnly()).simpleItem().register();
    public static void register() {
    }
}
