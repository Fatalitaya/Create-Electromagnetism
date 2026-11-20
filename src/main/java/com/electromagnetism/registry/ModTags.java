package com.electromagnetism.registry;

import com.electromagnetism.ElectromagnetismMod;

import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

public class ModTags {
    /** Data packs use this tag to select blocks eligible for extra growth lamp random ticks. */
    public static final TagKey<Block> AFFECTED_BY_LAMP = TagKey.create(Registries.BLOCK, ElectromagnetismMod.rl("affected_by_lamp"));
    /** Sable uses this tag to recognize the mod's light sources. */
    public static final TagKey<Block> SABLE_LIGHT = TagKey.create(Registries.BLOCK, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("sable", "light"));
}
