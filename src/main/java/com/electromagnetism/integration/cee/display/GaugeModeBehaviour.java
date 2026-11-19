package com.electromagnetism.integration.cee.display;

import com.electromagnetism.simulation.SimulationProtocol.Reading;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBoard;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsFormatter;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;

import java.util.List;

public class GaugeModeBehaviour extends ScrollValueBehaviour {
    public static final BehaviourType<GaugeModeBehaviour> TYPE = new BehaviourType<>("electromagnetism:gauge_mode");
    private static final List<String> MODES = List.of("rms", "instantaneous", "mean", "peak");

    public GaugeModeBehaviour(SmartBlockEntity gauge) {
        super(Component.translatable("electromagnetism.gauge.mode"), gauge, new GaugeControlSlot(12));
        between(0, MODES.size() - 1);
    }

    public double measurement(Reading reading, boolean voltage, RmsDisplayWindow.Sample rms) {
        return switch (value) {
            case 1 -> voltage ? reading.endVoltage() : reading.endCurrent();
            case 2 -> voltage ? reading.meanVoltage() : reading.meanCurrent();
            case 3 -> voltage ? reading.peakVoltage() : reading.peakCurrent();
            default -> rms == null ? Double.NaN : voltage ? rms.voltage() : rms.current();
        };
    }

    private static MutableComponent name(int index) {
        return Component.translatable("electromagnetism.gauge.mode." + MODES.get(index));
    }

    @Override public String formatValue() { return name(value).getString(); }
    @Override public BehaviourType<?> getType() { return TYPE; }
    @Override public int netId() { return 1; }

    @Override
    public ValueSettingsBoard createBoard(Player player, BlockHitResult hit) {
        return new ValueSettingsBoard(label, MODES.size() - 1, 1, List.of(Component.empty()),
                new ValueSettingsFormatter(setting -> name(setting.value())));
    }

    @Override
    public void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        tag.putString("GaugeDisplayMode", MODES.get(value));
    }

    @Override
    public void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        value = Math.max(0, MODES.indexOf(tag.getString("GaugeDisplayMode")));
    }
}
