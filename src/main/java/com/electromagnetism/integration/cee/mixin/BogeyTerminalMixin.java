package com.electromagnetism.integration.cee.mixin;

import com.electromagnetism.registry.ModDeviceTypes;
import com.george_vi.electroenergetics.content.wire_spool.HangingWireSpoolItem;
import com.george_vi.electroenergetics.content.wire_spool.WireSpoolItem;
import com.george_vi.electroenergetics.devices.device.DevicesSavedData;
import com.george_vi.electroenergetics.devices.device.SimulatedDeviceType;
import com.george_vi.electroenergetics.foundation.device.ElectricalDeviceBlock;
import com.george_vi.electroenergetics.foundation.device.SimpleNonTickingElectricalDevice;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = AbstractBogeyBlock.class, remap = false)
public abstract class BogeyTerminalMixin implements ElectricalDeviceBlock<SimpleNonTickingElectricalDevice> {
    @Override
    public SimulatedDeviceType<SimpleNonTickingElectricalDevice> getDevice() {
        return ModDeviceTypes.BOGEY_TERMINAL.get();
    }

    @Override
    public Vec3 getNodePosition(Level level, BlockPos pos, BlockState state, int id) {
        if (id == 2) {
            var bogey = (AbstractBogeyBlock<?>) state.getBlock();
            return new Vec3(0.5, bogey.isUpsideDown(state) ? 0 : 1, 0.5);
        }
        if (id != 0 && id != 1) return null;
        return state.getValue(AbstractBogeyBlock.AXIS) == Direction.Axis.Z
                ? new Vec3(id, 0.5, 0.5) : new Vec3(0.5, 0.5, id);
    }

    @Override
    public Map<Integer, Vec3> getNodePositions(Level level, BlockPos pos, BlockState state) {
        return Map.of(0, getNodePosition(level, pos, state, 0),
                1, getNodePosition(level, pos, state, 1),
                2, getNodePosition(level, pos, state, 2));
    }

    @Override
    public void ensureNodesExist(ServerLevel level, BlockPos pos, BlockState state) {
        var devices = DevicesSavedData.load(level);
        if (devices.getDevice(pos) == null)
            devices.addDevice(getDevice(), pos, getDefaultDeviceData(level, pos, state));
        ElectricalDeviceBlock.super.ensureNodesExist(level, pos, state);
    }

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void electromagnetism$wire(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<ItemInteractionResult> result) {
        if (stack.getItem() instanceof WireSpoolItem || stack.getItem() instanceof HangingWireSpoolItem)
            result.setReturnValue(ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION);
    }
}
