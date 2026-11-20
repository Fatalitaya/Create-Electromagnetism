package com.electromagnetism.content.electronics.mosfet;

import java.util.Map;

import com.electromagnetism.registry.ModBlockEntityTypes;
import com.electromagnetism.registry.ModDeviceTypes;
import com.george_vi.electroenergetics.foundation.base.DirectionalRolledDeviceBlock;
import com.george_vi.electroenergetics.foundation.nodes.NodeConfigurator;
import com.george_vi.electroenergetics.devices.device.SimulatedDeviceType;
import com.george_vi.electroenergetics.foundation.device.ElectricalDevice;
import com.simibubi.create.foundation.block.IBE;
import net.createmod.catnip.math.VoxelShaper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** World MOSFET terminals are numbered gate, drain, source. The block variant sets channel type. */
public class MosfetBlock extends DirectionalRolledDeviceBlock<MosfetDevice> implements IBE<MosfetBlockEntity> {
    private static final VoxelShaper SHAPE = new com.simibubi.create.AllShapes.Builder(
            Shapes.box(4 / 16d, 0, 3 / 16d, 12 / 16d, 2 / 16d, 13 / 16d)).forDirectional();
    private static final VoxelShaper SHAPE_ROLL = new com.simibubi.create.AllShapes.Builder(
            Shapes.box(3 / 16d, 0, 4 / 16d, 13 / 16d, 2 / 16d, 12 / 16d)).forDirectional();

    /**
     * The gate occupies one side and drain and source the other. Anchor wires at the pins on the
     * underside.
     */
    private static final NodeConfigurator NODES = new NodeConfigurator.Builder()
            .add(13f, 0f, 8f)
            .add(3f, 0f, 5f)
            .add(3f, 0f, 11f)
            .simple(Direction.UP);

    public MosfetBlock(Properties properties) {
        super(properties);
    }

    public boolean isPChannel() {
        return false;
    }

    @Override
    public SimulatedDeviceType<MosfetDevice> getDevice() {
        return ModDeviceTypes.N_MOSFET.get();
    }

    /**
     * Copy threshold voltage, on-resistance and the drain current setting from the block entity at
     * creation.
     */
    @Override
    public CompoundTag getDefaultDeviceData(Level level, BlockPos pos, BlockState state) {
        CompoundTag tag = new CompoundTag();
        if (level.getBlockEntity(pos) instanceof MosfetBlockEntity be) {
            tag.putDouble("Vth", be.getVth());
            tag.putDouble("RdsOn", be.getRdsOn());
            tag.putDouble("IdMax", be.getIdMax());
        }
        return tag;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return (state.getValue(ROLL) ? SHAPE_ROLL : SHAPE).get(state.getValue(FACING));
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
    public MutableComponent getNodeLabel(Level level, BlockPos pos, BlockState state, int id) {
        return switch (id) {
            case 0 -> Component.translatable("electromagnetism.nodes.gate");
            case 1 -> Component.translatable("electromagnetism.nodes.drain");
            default -> Component.translatable("electromagnetism.nodes.source");
        };
    }

    @Override
    public float getNodeSize(Level level, BlockPos pos, BlockState state, int id) {
        return 2 / 16f;
    }

    private static NodeConfigurator nodes(BlockState state) {
        return state.getValue(ROLL) ? NODES.rotate(new Vec3(0, -90, 0)) : NODES;
    }

    @Override
    public Class<MosfetBlockEntity> getBlockEntityClass() {
        return MosfetBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends MosfetBlockEntity> getBlockEntityType() {
        return ModBlockEntityTypes.MOSFET.get();
    }
}
