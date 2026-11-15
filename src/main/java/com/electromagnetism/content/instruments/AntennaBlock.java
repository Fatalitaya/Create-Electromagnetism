package com.electromagnetism.content.instruments;

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

public class AntennaBlock extends SimpleElectricalDeviceBlock<AntennaDevice> implements IBE<InstrumentBlockEntity> {
    public static final DirectionProperty HORIZONTAL_FACING = BlockStateProperties.HORIZONTAL_FACING;

    private static final VoxelShaper SHAPE = VoxelShaper.forHorizontal(
            Shapes.or(Block.box(4,0,4,12,3,12),Block.box(7,3,7,9,16,9)), Direction.SOUTH);

    private static final NodeConfigurator NODES = new NodeConfigurator.Builder()
            .add(5f, 3f, 8f)
            .add(11f, 3f, 8f)
            .simple(Direction.SOUTH);

    public AntennaBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(HORIZONTAL_FACING, Direction.NORTH));
    }


    @Override
    protected void createBlockStateDefinition(net.minecraft.world.level.block.state.StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HORIZONTAL_FACING);
    }

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
    public SimulatedDeviceType<AntennaDevice> getDevice() {
        return ModDeviceTypes.ANTENNA.get();
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
        return Component.translatable("electromagnetism.nodes.antenna_terminal");
    }

    @Override
    public float getNodeSize(Level level, BlockPos pos, BlockState state, int id) {
        return 2 / 16f;
    }

    @Override protected net.minecraft.world.InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,
            net.minecraft.world.entity.player.Player player,net.minecraft.world.phys.BlockHitResult hit) {
        if(!player.getMainHandItem().isEmpty()||!player.getOffhandItem().isEmpty())return net.minecraft.world.InteractionResult.PASS;
        if(!level.isClientSide&&level instanceof net.minecraft.server.level.ServerLevel server) {
            var device=com.george_vi.electroenergetics.devices.device.DevicesSavedData.load(server).getDevice(pos);
            if(device instanceof AntennaDevice antenna) {
                if(player.isShiftKeyDown())antenna.couplingCapacitance=antenna.couplingCapacitance>=1e-3?1e-6:antenna.couplingCapacitance*10;
                player.displayClientMessage(Component.translatable("electromagnetism.antenna.settings",antenna.radiationResistance,antenna.couplingCapacitance),true);
            }
        }
        return net.minecraft.world.InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override
    public Class<InstrumentBlockEntity> getBlockEntityClass() {
        return InstrumentBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends InstrumentBlockEntity> getBlockEntityType() {
        return ModBlockEntityTypes.INSTRUMENT.get();
    }
}
