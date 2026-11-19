package com.electromagnetism.content.electronics;

import com.electromagnetism.registry.ModDeviceTypes;
import com.george_vi.electroenergetics.CEENodeConfigurations;
import com.george_vi.electroenergetics.CEEShapes;
import com.george_vi.electroenergetics.devices.device.SimulatedDeviceType;
import com.george_vi.electroenergetics.foundation.base.DirectionalRolledDeviceBlock;
import com.george_vi.electroenergetics.foundation.nodes.NodeConfigurator;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class PowerChokeBlock extends DirectionalRolledDeviceBlock<PowerChokeDevice> {
    public PowerChokeBlock(Properties properties) {
        super(properties);
    }

    @Override
    public SimulatedDeviceType<PowerChokeDevice> getDevice() {
        return ModDeviceTypes.POWER_CHOKE.get();
    }

    private static NodeConfigurator nodes(BlockState state) {
        return state.getValue(ROLL) ? CEENodeConfigurations.ELECTRONIC_10.rotate(new Vec3(0, -90, 0))
                : CEENodeConfigurations.ELECTRONIC_10;
    }

    @Override
    public Map<Integer, Vec3> getNodePositions(Level level, BlockPos pos, BlockState state) {
        return nodes(state).getNodes(state.getValue(FACING));
    }

    @Override
    public Vec3 getNodePosition(Level level, BlockPos pos, BlockState state, int id) {
        return nodes(state).getNodePos(state.getValue(FACING), id);
    }

    @Override
    public float getNodeSize(Level level, BlockPos pos, BlockState state, int id) {
        return 0.125f;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return CEEShapes.INDUCTOR.get(state.getValue(FACING));
    }
}
