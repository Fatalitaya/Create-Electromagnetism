package com.electromagnetism.client;

import com.electromagnetism.ElectromagnetismMod;
import com.electromagnetism.registry.ModBlocks;
import com.george_vi.electroenergetics.CEEBlocks;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

@EventBusSubscriber(modid = ElectromagnetismMod.ID, value = Dist.CLIENT)
public final class ComponentRatingTooltip {
    @SubscribeEvent
    public static void addRating(ItemTooltipEvent event) {
        var stack = event.getItemStack();
        String component;
        if (stack.is(CEEBlocks.DIODE.get().asItem())) component = "diode";
        else if (stack.is(ModBlocks.HIGH_VOLTAGE_RECTIFIER.get().asItem())) component = "high_voltage_rectifier";
        else if (stack.is(ModBlocks.ZENER_DIODE.get().asItem())) component = "zener_diode";
        else if (stack.is(ModBlocks.POWER_CHOKE.get().asItem())) component = "power_choke";
        else return;
        event.getToolTip().add(Component.translatable("electromagnetism.rating." + component));
    }
}
