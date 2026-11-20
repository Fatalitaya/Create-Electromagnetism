package com.electromagnetism.content.electromechanics.excitation;

import com.electromagnetism.registry.ModBlockEntityTypes;
import com.electromagnetism.registry.ModDeviceTypes;
import com.george_vi.electroenergetics.content.rotor.AlternatorRotorBlock;
import com.george_vi.electroenergetics.devices.device.SimulatedDeviceType;
import com.george_vi.electroenergetics.foundation.CEELang;
import com.george_vi.electroenergetics.foundation.base.DirectionalRolledDeviceBlock;
import com.george_vi.electroenergetics.foundation.nodes.NodeConfigurator;
import com.simibubi.create.AllShapes;
import com.simibubi.create.foundation.block.IBE;
import net.createmod.catnip.data.Iterate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Map;

/** Both excitation terminals lie on the open face of the stator and follow its facing and roll. */
public class ExcitationStatorBlock extends DirectionalRolledDeviceBlock<ExcitationStatorDevice> implements IBE<ExcitationStatorBlockEntity> {
    /** Show the complete yoke when four active stators surround the rotor. */
    public static final BooleanProperty FULL = BooleanProperty.create("full");

    /**
     * The poles face down in the FACING=UP reference model. Place both terminals at the pole tips
     * on the underside.
     */
    public static final NodeConfigurator NODES = new NodeConfigurator.Builder()
            .add(8f, 0f, 4f)
            .add(8f, 0f, 12f)
            .simple(Direction.UP);

    public ExcitationStatorBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FULL, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(FULL);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return AllShapes.CASING_3PX.get(state.getValue(FACING));
    }

    @Override
    protected VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return Shapes.empty();
    }

    @Override
    public SimulatedDeviceType<ExcitationStatorDevice> getDevice() {
        return ModDeviceTypes.EXCITATION_STATOR.get();
    }

    @Override
    public Map<Integer, Vec3> getNodePositions(Level level, BlockPos pos, BlockState state) {
        return NODES.getNodes(state.getValue(FACING), state.getValue(ROLL));
    }

    @Override
    public Vec3 getNodePosition(Level level, BlockPos pos, BlockState state, int id) {
        return NODES.getNodePos(state.getValue(FACING), state.getValue(ROLL), id);
    }

    @Override
    public MutableComponent getNodeLabel(Level level, BlockPos pos, BlockState state, int id) {
        return CEELang.nodeLabel(id == 0 ? "positive" : "negative");
    }

    /** Adjacent stators aligned on the terminal axis can be connected end to end. */
    public static Direction.Axis terminalAxis(BlockState state) {
        return getRotorAxis(state);
    }

    static boolean isStator(BlockGetter level, BlockPos pos) {
        return level.getBlockState(pos).getBlock() instanceof ExcitationStatorBlock;
    }

    /** Excitation requires the stator to face the rotor with a matching rotor axis. */
    public static boolean canPowerRotor(BlockPos pos, BlockState state, BlockPos rotorPos, BlockState rotorState) {
        if (!(rotorState.getBlock() instanceof AlternatorRotorBlock))
            return false;
        return pos.relative(state.getValue(FACING)).equals(rotorPos)
                && rotorState.getValue(AlternatorRotorBlock.AXIS) == getRotorAxis(state);
    }

    public static Direction.Axis getRotorAxis(BlockState state) {
        Direction facing = state.getValue(FACING);
        boolean roll = state.getValue(ROLL);
        if (facing.getAxis().isHorizontal())
            return roll ? facing.getClockWise().getAxis() : Direction.Axis.Y;
        return roll ? Direction.Axis.X : Direction.Axis.Z;
    }

    /**
     * A complete ring requires a matching inward-facing stator on each of the four sides
     * perpendicular to the shaft.
     */
    private static boolean shouldBeFull(BlockState state, BlockGetter level, BlockPos pos) {
        Direction facing = state.getValue(FACING);
        Direction.Axis rotorAxis = getRotorAxis(state);

        BlockPos rotorPos = pos.relative(facing);
        BlockState rotorState = level.getBlockState(rotorPos);

        if (!(rotorState.getBlock() instanceof AlternatorRotorBlock)
                || rotorState.getValue(AlternatorRotorBlock.AXIS) != rotorAxis)
            return false;

        for (Direction dir : Iterate.directions) {
            if (dir.getAxis() == rotorAxis)
                continue;
            BlockPos otherPos = rotorPos.relative(dir);
            BlockState otherState = level.getBlockState(otherPos);
            if (!(otherState.getBlock() instanceof ExcitationStatorBlock)
                    || !canPowerRotor(otherPos, otherState, rotorPos, rotorState))
                return false;
        }
        return true;
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                   BlockPos neighborPos, boolean movedByPiston) {
        BlockPos rotorPos = pos.relative(state.getValue(FACING));
        BlockState rotorState = level.getBlockState(rotorPos);
        boolean full = shouldBeFull(state, level, pos);
        if (full != state.getValue(FULL)) {
            level.setBlockAndUpdate(pos, state.setValue(FULL, full));
            // Refresh the neighbouring rotor's appearance when the ring changes.
            level.updateNeighborsAtExceptFromFacing(rotorPos, rotorState.getBlock(), state.getValue(FACING).getOpposite());
        }
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        BlockState updated = super.updateShape(state, direction, neighborState, level, pos, neighborPos);
        return updated.setValue(FULL, shouldBeFull(state, level, pos));
    }

    @Override
    public Class<ExcitationStatorBlockEntity> getBlockEntityClass() {
        return ExcitationStatorBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends ExcitationStatorBlockEntity> getBlockEntityType() {
        return ModBlockEntityTypes.EXCITATION_STATOR.get();
    }
}
