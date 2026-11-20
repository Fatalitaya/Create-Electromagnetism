package com.electromagnetism.registry;

import com.electromagnetism.ElectromagnetismMod;

import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Register the ordinary bell's loop and tail, based on the PowerGrid alarm bell, and the special
 * bell loops.
 */
public class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(Registries.SOUND_EVENT, ElectromagnetismMod.ID);
    public static final DeferredHolder<SoundEvent,SoundEvent> NATIVE_RECEIVER=SOUND_EVENTS.register("native_receiver",()->SoundEvent.createVariableRangeEvent(ElectromagnetismMod.rl("native_receiver")));

    public static final DeferredHolder<SoundEvent, SoundEvent> ALARM_BELL =
            SOUND_EVENTS.register("alarm_bell", () -> SoundEvent.createVariableRangeEvent(ElectromagnetismMod.rl("alarm_bell")));

    public static final DeferredHolder<SoundEvent, SoundEvent> ALARM_BELL_END =
            SOUND_EVENTS.register("alarm_bell_end", () -> SoundEvent.createVariableRangeEvent(ElectromagnetismMod.rl("alarm_bell_end")));

    public static final DeferredHolder<SoundEvent, SoundEvent> ICE_BELL =
            SOUND_EVENTS.register("ice_bell", () -> SoundEvent.createVariableRangeEvent(ElectromagnetismMod.rl("ice_bell")));

    public static final DeferredHolder<SoundEvent, SoundEvent> NAILONG_BELL =
            SOUND_EVENTS.register("nailong_bell", () -> SoundEvent.createVariableRangeEvent(ElectromagnetismMod.rl("nailong_bell")));

    public static void register(IEventBus modEventBus) {
        SOUND_EVENTS.register(modEventBus);
    }
}
