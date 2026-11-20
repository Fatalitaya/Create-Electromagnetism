package com.electromagnetism.client;

import com.electromagnetism.ElectromagnetismMod;
import com.electromagnetism.content.electronics.ComponentRatings;
import com.electromagnetism.content.lighting.bulb.LightBulbItem;
import com.electromagnetism.registry.ModBlocks;
import com.electromagnetism.registry.ModItems;
import com.george_vi.electroenergetics.CEEBlocks;
import com.george_vi.electroenergetics.client.ElectricStatsTooltipModifier;
import com.george_vi.electroenergetics.client.ElectricStatsTooltipModifier.ElectricStatEntry;
import com.george_vi.electroenergetics.client.ElectricStatsTooltipModifier.ElectricStatSet;
import com.george_vi.electroenergetics.foundation.CEELang;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import static com.electromagnetism.content.electronics.ComponentRatings.*;
import static com.george_vi.electroenergetics.client.ElectricStatsTooltipModifier.ALL_ENTRIES;
import static com.george_vi.electroenergetics.client.ElectricStatsTooltipModifier.ElectricStatType.*;

@EventBusSubscriber(modid = ElectromagnetismMod.ID, value = Dist.CLIENT)
public final class ComponentRatingTooltip {
    public static void register(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ALL_ENTRIES.register(CEEBlocks.DIODE.asItem(), rectifier(DIODE));
            ALL_ENTRIES.register(ModBlocks.HIGH_VOLTAGE_RECTIFIER.asItem(), rectifier(HIGH_VOLTAGE_RECTIFIER));
            ALL_ENTRIES.register(ModBlocks.ZENER_DIODE.asItem(), new ElectricStatSet()
                    .add(new ElectricStatEntry(VOLTAGE, "electromagnetism.zener_voltage", () -> ZENER_VOLTS))
                    .add(new ElectricStatEntry(MAX_CURRENT, "electromagnetism.reverse_test_current", () -> ZENER_TEST_AMPERES))
                    .add(new ElectricStatEntry(RESISTANCE, "electromagnetism.dynamic_resistance", () -> ZENER_DYNAMIC_OHMS))
                    .add(new ElectricStatEntry(POWER, "electromagnetism.continuous_power", () -> ZENER_CONTINUOUS_WATTS)));
            ALL_ENTRIES.register(ModBlocks.POWER_CHOKE.asItem(), new ElectricStatSet()
                    .addResistance(() -> CHOKE_WINDING_OHMS)
                    .add(new ElectricStatEntry(MAX_CURRENT, "electromagnetism.continuous_current", () -> CHOKE_CONTINUOUS_AMPERES))
                    .add(new ElectricStatEntry(VOLTAGE, "electromagnetism.insulation_voltage", () -> CHOKE_INSULATION_VOLTS)));
            registerBulb(ModItems.LV_LIGHT_BULB.get());
            registerBulb(ModItems.LIGHT_BULB.get());
            registerBulb(ModItems.GROWTH_LAMP.get());
        });
    }

    private static ElectricStatSet rectifier(ComponentRatings.Rectifier rating) {
        return new ElectricStatSet()
                .add(new ElectricStatEntry(VOLTAGE, "electromagnetism.peak_reverse", rating::peakReverseVolts))
                .add(new ElectricStatEntry(MAX_CURRENT, "electromagnetism.continuous_forward", rating::continuousForwardAmperes));
    }

    private static void registerBulb(LightBulbItem bulb) {
        var rating = bulb.thermalProperties();
        ALL_ENTRIES.register(bulb, new ElectricStatSet()
                .addVoltage(rating::ratedVoltage)
                .addPower(rating::ratedPower));
    }

    @SubscribeEvent
    public static void addRating(ItemTooltipEvent event) {
        var item = event.getItemStack().getItem();
        // CEE already invokes this modifier for items registered under its own namespace.
        if (!BuiltInRegistries.ITEM.getKey(item).getNamespace().equals(ElectromagnetismMod.ID)) return;
        new ElectricStatsTooltipModifier(item).modify(event);
        if (item == ModBlocks.POWER_CHOKE.asItem()) {
            // CEE formats inductance but has no ElectricStatType for it.
            event.getToolTip().add(Component.translatable("electromagnetism.rating.inductance").withStyle(ChatFormatting.GRAY));
            CEELang.formatInductance(CHOKE_HENRIES).addTo(event.getToolTip());
        }
    }
}
