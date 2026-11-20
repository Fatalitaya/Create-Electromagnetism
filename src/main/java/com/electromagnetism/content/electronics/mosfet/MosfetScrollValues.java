package com.electromagnetism.content.electronics.mosfet;

import java.util.List;

import com.google.common.collect.ImmutableList;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBoard;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsFormatter;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBehaviour.ValueSettings;

import net.createmod.catnip.lang.LangNumberFormat;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Give each control its own behaviour type, network index and persistence key so editing one
 * parameter preserves the others.
 */
public final class MosfetScrollValues {
    private MosfetScrollValues() {
    }

    /** Store threshold voltage in mV and display it in V. */
    public static class Vth extends ScrollValueBehaviour {
        public static final BehaviourType<Vth> TYPE = new BehaviourType<>("electromagnetism:mosfet_vth");

        public Vth(Component label, SmartBlockEntity be, ValueBoxTransform slot) {
            super(label, be, slot);
            between(100, 20_000);
            value = 2000;
        }

        @Override
        public BehaviourType<?> getType() {
            return TYPE;
        }

        @Override
        public int netId() {
            return 0;
        }

        @Override
        public void write(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
            nbt.putInt("MosfetVth", value);
        }

        @Override
        public void read(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
            value = Mth.clamp(nbt.getInt("MosfetVth"), 100, 20_000);
        }

        @Override
        public ValueSettingsBoard createBoard(Player player, BlockHitResult hitResult) {
            return new ValueSettingsBoard(label, 190, 10,
                    ImmutableList.of(Component.literal("\u00d70.1 V"), Component.literal("\u00d71 V")),
                    new ValueSettingsFormatter(vs -> Component.literal(
                            LangNumberFormat.format(indexToVolts(vs.value(), vs.row())) + " V")
                            .withStyle(ChatFormatting.AQUA)));
        }

        @Override
        public void setValueSettings(Player player, ValueSettings valueSetting, boolean ctrlHeld) {
            double volts = indexToVolts(valueSetting.value(), valueSetting.row());
            int stored = Mth.clamp((int) Math.round(volts * 1000), 100, 20_000);
            if (stored != value) {
                playFeedbackSound(this);
                setValue(stored);
            }
        }

        @Override
        public ValueSettings getValueSettings() {
            double v = value / 1000d;
            if (v < 2)
                return new ValueSettings(0, Mth.clamp((int) Math.round(v * 10), 1, 19));
            return new ValueSettings(1, Mth.clamp((int) Math.round(v), 2, 20));
        }

        @Override
        public String formatValue() {
            return (value / 1000d) + " V";
        }

        public double getVth() {
            return value / 1000d;
        }

        /** The voltage rows meet at 2 V. The lower row steps by 0.1 V and the upper by 1 V. */
        private static double indexToVolts(int i, int row) {
            if (row == 0)
                return Math.max(0.1, i / 10d);
            return Math.max(2, i);
        }
    }

    /** Store on-resistance in mΩ and provide an Ω range in the editor. */
    public static class RdsOn extends ScrollValueBehaviour {
        public static final BehaviourType<RdsOn> TYPE = new BehaviourType<>("electromagnetism:mosfet_rdson");

        public RdsOn(Component label, SmartBlockEntity be, ValueBoxTransform slot) {
            super(label, be, slot);
            between(1, 100_000);
            value = 100;
        }

        @Override
        public BehaviourType<?> getType() {
            return TYPE;
        }

        @Override
        public int netId() {
            return 1;
        }

        @Override
        public void write(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
            nbt.putInt("MosfetRdsOn", value);
        }

        @Override
        public void read(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
            value = Mth.clamp(nbt.getInt("MosfetRdsOn"), 1, 100_000);
        }

        @Override
        public ValueSettingsBoard createBoard(Player player, BlockHitResult hitResult) {
            return new ValueSettingsBoard(label, 190, 10,
                    ImmutableList.of(Component.literal("m\u03a9"), Component.literal("\u03a9")),
                    new ValueSettingsFormatter(vs -> Component.literal(
                            LangNumberFormat.format(indexToOhms(vs.value(), vs.row())) + " \u03a9")
                            .withStyle(ChatFormatting.AQUA)));
        }

        @Override
        public void setValueSettings(Player player, ValueSettings valueSetting, boolean ctrlHeld) {
            double ohms = indexToOhms(valueSetting.value(), valueSetting.row());
            int stored = Mth.clamp((int) Math.round(ohms * 1000), 1, 100_000);
            if (stored != value) {
                playFeedbackSound(this);
                setValue(stored);
            }
        }

        /**
         * Use the same segment boundaries for selection and display to retain low-resistance
         * resolution.
         */
        @Override
        public ValueSettings getValueSettings() {
            double mOhm = value;
            if (mOhm < 100)
                return new ValueSettings(0, (int) Math.max(1, mOhm));
            if (mOhm < 1000)
                return new ValueSettings(0, (int) (mOhm / 10) + 90);
            if (mOhm < 100_000)
                return new ValueSettings(1, (int) (mOhm / 1000));
            return new ValueSettings(1, (int) (mOhm / 10_000) + 90);
        }

        @Override
        public String formatValue() {
            double o = value / 1000d;
            return o >= 1 ? o + " \u03a9" : value + " m\u03a9";
        }

        public double getRdsOn() {
            return value / 1000d;
        }

        private static double indexToOhms(int i, int row) {
            if (row == 0)
                return (i < 100 ? Math.max(1, i) : (i - 90) * 10d) / 1000d;
            return (i < 100 ? i * 1000d : (i - 90) * 10_000d) / 1000d;
        }
    }

    /** Store drain current in mA and select mA or A for display according to magnitude. */
    public static class IdMax extends ScrollValueBehaviour {
        public static final BehaviourType<IdMax> TYPE = new BehaviourType<>("electromagnetism:mosfet_idmax");

        public IdMax(Component label, SmartBlockEntity be, ValueBoxTransform slot) {
            super(label, be, slot);
            between(1, 1_000_000);
            value = 10_000;
        }

        @Override
        public BehaviourType<?> getType() {
            return TYPE;
        }

        @Override
        public int netId() {
            return 2;
        }

        @Override
        public void write(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
            nbt.putInt("MosfetIdMax", value);
        }

        @Override
        public void read(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
            value = Mth.clamp(nbt.getInt("MosfetIdMax"), 1, 1_000_000);
        }

        @Override
        public ValueSettingsBoard createBoard(Player player, BlockHitResult hitResult) {
            return new ValueSettingsBoard(label, 190, 10,
                    ImmutableList.of(Component.literal("mA"), Component.literal("A")),
                    new ValueSettingsFormatter(vs -> Component.literal(
                            LangNumberFormat.format(indexToAmps(vs.value(), vs.row())) + " A")
                            .withStyle(ChatFormatting.AQUA)));
        }

        @Override
        public void setValueSettings(Player player, ValueSettings valueSetting, boolean ctrlHeld) {
            double amps = indexToAmps(valueSetting.value(), valueSetting.row());
            int stored = Mth.clamp((int) Math.round(amps * 1000), 1, 1_000_000);
            if (stored != value) {
                playFeedbackSound(this);
                setValue(stored);
            }
        }

        @Override
        public ValueSettings getValueSettings() {
            double mA = value;
            if (mA < 100)
                return new ValueSettings(0, (int) Math.max(1, mA));
            if (mA < 1000)
                return new ValueSettings(0, (int) (mA / 10) + 90);
            if (mA < 100_000)
                return new ValueSettings(1, (int) (mA / 1000));
            return new ValueSettings(1, (int) (mA / 10_000) + 90);
        }

        @Override
        public String formatValue() {
            double a = value / 1000d;
            return a >= 1 ? a + " A" : value + " mA";
        }

        public double getIdMax() {
            return value / 1000d;
        }

        private static double indexToAmps(int i, int row) {
            if (row == 0)
                return (i < 100 ? Math.max(1, i) : (i - 90) * 10d) / 1000d;
            return ((i < 100 ? i * 1000d : (i - 90) * 10_000d)) / 1000d;
        }
    }
}
