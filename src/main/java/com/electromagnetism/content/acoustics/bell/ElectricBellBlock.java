package com.electromagnetism.content.acoustics.bell;

import com.electromagnetism.registry.ModBlockEntityTypes;
import com.electromagnetism.registry.ModDeviceTypes;
import com.george_vi.electroenergetics.devices.device.SimulatedDeviceType;
import com.george_vi.electroenergetics.foundation.base.SimpleElectricalDeviceBlock;
import com.george_vi.electroenergetics.foundation.nodes.NodeConfigurator;
import com.simibubi.create.foundation.block.IBE;
import net.createmod.catnip.math.VoxelShaper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Map;

/**
 * Wall-mounted bell based on the PowerGrid alarm bell's shape and sound. CEE terminals power the
 * coil, and the client plays the synchronized result.
 */
public class ElectricBellBlock extends SimpleElectricalDeviceBlock<ElectricBellDevice> implements IBE<ElectricBellBlockEntity> {
    public static final DirectionProperty HORIZONTAL_FACING = BlockStateProperties.HORIZONTAL_FACING;

    /**
     * The south-facing model has its backplate on the north side. Rotate terminals and collision
     * geometry together.
     */
    private static final VoxelShaper SHAPE = VoxelShaper.forHorizontal(
            Shapes.box(4 / 16d, 4 / 16d, 0 / 16d, 12 / 16d, 12 / 16d, 6 / 16d), Direction.SOUTH);

    /** Terminals sit on the backplate's upper edge. Coordinates are in unrotated model pixels. */
    private static final NodeConfigurator NODES = new NodeConfigurator.Builder()
            .add(6.5f, 12f, 0.5f)
            .add(9.5f, 12f, 0.5f)
            .simple(Direction.SOUTH);

    public ElectricBellBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(HORIZONTAL_FACING, Direction.NORTH));
    }

    public boolean isIceBell() {
        return false;
    }

    public boolean isNailongBell() {
        return false;
    }

    @Override
    protected void createBlockStateDefinition(net.minecraft.world.level.block.state.StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HORIZONTAL_FACING);
    }

    /**
     * On a wall the opening faces outward. Placement on a floor or ceiling follows the player's
     * horizontal facing.
     */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Direction face = ctx.getClickedFace();
        Direction facing = face.getAxis().isHorizontal() ? face : ctx.getHorizontalDirection().getOpposite();
        return defaultBlockState().setValue(HORIZONTAL_FACING, facing);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE.get(state.getValue(HORIZONTAL_FACING));
    }

    @Override
    public SimulatedDeviceType<ElectricBellDevice> getDevice() {
        return ModDeviceTypes.ELECTRIC_BELL.get();
    }

    @Override
    public Map<Integer, Vec3> getNodePositions(Level level, BlockPos pos, BlockState state) {
        return NODES.getNodes(state.getValue(HORIZONTAL_FACING));
    }

    @Override
    public Vec3 getNodePosition(Level level, BlockPos pos, BlockState state, int id) {
        return NODES.getNodePos(state.getValue(HORIZONTAL_FACING), id);
    }

    @Override
    public MutableComponent getNodeLabel(Level level, BlockPos pos, BlockState state, int id) {
        return Component.translatable("electromagnetism.nodes.bell_terminal");
    }

    @Override
    public float getNodeSize(Level level, BlockPos pos, BlockState state, int id) {
        return 2 / 16f;
    }

    @Override
    public Class<ElectricBellBlockEntity> getBlockEntityClass() {
        return ElectricBellBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends ElectricBellBlockEntity> getBlockEntityType() {
        return ModBlockEntityTypes.ELECTRIC_BELL.get();
    }
}
