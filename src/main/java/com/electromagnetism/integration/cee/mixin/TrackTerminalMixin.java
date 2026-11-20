package com.electromagnetism.integration.cee.mixin;

import com.electromagnetism.integration.cee.nativebridge.NativeRunningRails;
import com.electromagnetism.registry.ModDeviceTypes;
import com.george_vi.electroenergetics.content.wire_spool.HangingWireSpoolItem;
import com.george_vi.electroenergetics.content.wire_spool.WireSpoolItem;
import com.george_vi.electroenergetics.devices.device.DevicesSavedData;
import com.george_vi.electroenergetics.devices.device.SimulatedDeviceType;
import com.george_vi.electroenergetics.foundation.device.ElectricalDeviceBlock;
import com.george_vi.electroenergetics.foundation.device.SimpleNonTickingElectricalDevice;
import com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData;
import com.simibubi.create.content.trains.track.TrackBlock;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = TrackBlock.class, remap = false)
public abstract class TrackTerminalMixin implements ElectricalDeviceBlock<SimpleNonTickingElectricalDevice> {
    @Override
    public SimulatedDeviceType<SimpleNonTickingElectricalDevice> getDevice() {
        return ModDeviceTypes.RAIL_TERMINAL.get();
    }

    @Override
    public Map<Integer, Vec3> getNodePositions(Level level, BlockPos pos, BlockState state) {
        var point = getNodePosition(level, pos, state, 0);
        return point == null ? Map.of() : Map.of(0, point);
    }

    @Override
    public Vec3 getNodePosition(Level level, BlockPos pos, BlockState state, int id) {
        var track = (TrackBlock) (Object) this;
        if (id != 0 || !NativeRunningRails.conductiveMaterial(track.getMaterial())
                || track.getTrackAxes(level, pos, state).size() != 1) return null;
        return new Vec3(0.5, track.getElevationAtCenter(level, pos, state) + 0.125, 0.5);
    }

    @Override
    public void ensureNodesExist(ServerLevel level, BlockPos pos, BlockState state) {
        // Track laid in an older save can acquire its device on first wiring.
        var devices = DevicesSavedData.load(level);
        if (devices.getDevice(pos) == null && getNodePosition(level, pos, state, 0) != null)
            devices.addDevice(getDevice(), pos, getDefaultDeviceData(level, pos, state));
        ElectricalDeviceBlock.super.ensureNodesExist(level, pos, state);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void electromagnetism$refreshTerminal(BlockState state, ServerLevel level, BlockPos pos,
            RandomSource random, CallbackInfo ci) {
        var infrastructure = InfrastructureSavedData.load(level);
        if (!infrastructure.getNodesAt(pos).isEmpty())
            ensureNodesExist(level, pos, state);
    }

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void electromagnetism$useSpool(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<ItemInteractionResult> result) {
        if ((stack.getItem() instanceof WireSpoolItem || stack.getItem() instanceof HangingWireSpoolItem)
                && getNodePosition(level, pos, state, 0) != null)
            result.setReturnValue(ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION);
    }
}
