package com.electromagnetism.content.electromechanics;

import com.electromagnetism.registry.ModBlockEntityTypes;
import com.electromagnetism.registry.ModDeviceTypes;
import com.george_vi.electroenergetics.CEENodeConfigurations;
import com.george_vi.electroenergetics.devices.device.DevicesSavedData;
import com.george_vi.electroenergetics.devices.device.SimulatedDeviceType;
import com.george_vi.electroenergetics.foundation.base.DirectionalKineticElectricBlock;
import com.george_vi.electroenergetics.foundation.device.SimpleNonTickingElectricalDevice;
import com.george_vi.electroenergetics.foundation.nodes.NodeConfigurator;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import java.util.Map;

public class AcMotorBlock extends DirectionalKineticElectricBlock<SimpleNonTickingElectricalDevice> implements IBE<AcMotorBlockEntity> {
    private static final NodeConfigurator THREE_PHASE_NODES = new NodeConfigurator.Builder()
            .add(2, 13, 8).add(14, 13, 8).add(8, 13, 14).simple();
    private final NodeConfigurator nodes;

    public AcMotorBlock(Properties properties, boolean threePhase) {
        super(properties);
        nodes = threePhase ? THREE_PHASE_NODES : CEENodeConfigurations.ELECTRIC_MOTOR;
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    @Override
    public SimulatedDeviceType<SimpleNonTickingElectricalDevice> getDevice() {
        return ModDeviceTypes.AC_MOTOR_TERMINALS.get();
    }

    @Override
    public Map<Integer, Vec3> getNodePositions(Level level, BlockPos pos, BlockState state) {
        return nodes.getNodes(state.getValue(FACING), false);
    }

    @Override
    public Vec3 getNodePosition(Level level, BlockPos pos, BlockState state, int id) {
        return nodes.getNodePos(state.getValue(FACING), false, id);
    }

    @Override
    public MutableComponent getNodeLabel(Level level, BlockPos pos, BlockState state, int id) {
        return Component.literal(switch (id) {
            case 0 -> "U";
            case 1 -> "V";
            case 2 -> "W";
            default -> "";
        });
    }

    @Override
    public void ensureNodesExist(ServerLevel level, BlockPos pos, BlockState state) {
        // Motors placed before terminal registration acquire their device on first wiring.
        var devices = DevicesSavedData.load(level);
        if (devices.getDevice(pos) == null)
            devices.addDevice(getDevice(), pos, getDefaultDeviceData(level, pos, state));
        super.ensureNodesExist(level, pos, state);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getClickedFace());
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(FACING).getAxis();
    }

    @Override
    public boolean hasShaftTowards(LevelReader level, BlockPos pos, BlockState state, Direction face) {
        return face.getAxis() == getRotationAxis(state);
    }

    @Override
    public Class<AcMotorBlockEntity> getBlockEntityClass() {
        return AcMotorBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends AcMotorBlockEntity> getBlockEntityType() {
        return ModBlockEntityTypes.AC_MOTOR.get();
    }
}
