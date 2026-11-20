package com.electromagnetism.content.lighting.fixture;

import com.electromagnetism.registry.ModBlockEntityTypes;
import com.electromagnetism.registry.ModDeviceTypes;
import com.electromagnetism.content.lighting.bulb.ILightBulb;
import com.george_vi.electroenergetics.devices.device.SimulatedDeviceType;
import com.george_vi.electroenergetics.foundation.base.DirectionalRolledDeviceBlock;
import com.george_vi.electroenergetics.foundation.nodes.NodeConfigurator;
import com.simibubi.create.foundation.block.IBE;
import net.createmod.catnip.math.VoxelShaper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The fixture handles bulbs, dye and wiring. Brightness levels drive world lighting and rendering. */
public class LightFixtureBlock extends DirectionalRolledDeviceBlock<LightFixtureDevice> implements IBE<LightFixtureBlockEntity> {
    public static final IntegerProperty POWER = IntegerProperty.create("power", 0, 2);
    /** Offset the bulb model 3/16 of a block outward from the base. */
    public static final Vec3 BULB_MODEL_OFFSET = new Vec3(0, 3 / 16f, 0);

    private static final VoxelShaper SHAPE = VoxelShaper.forDirectional(box(3.5, 0, 3.5, 12.5, 3, 12.5), Direction.UP);
    public LightFixtureBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(POWER, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(POWER);
    }

    @Override
    public boolean hasDynamicLightEmission(BlockState state) {
        return true;
    }

    @Override
    public int getLightEmission(BlockState state, BlockGetter level, BlockPos pos) {
        return switch (state.getValue(POWER)) {
            case 1 -> ILightBulb.LIGHT_LEVEL_LOW_POWER;
            case 2 -> ILightBulb.LIGHT_LEVEL_FULL_POWER;
            default -> 0;
        };
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE.get(state.getValue(FACING));
    }

    @Override
    public SimulatedDeviceType<LightFixtureDevice> getDevice() {
        return ModDeviceTypes.LIGHT_FIXTURE.get();
    }

    /**
     * Specify wire anchors in the local pixel coordinates of the mounting model. Wall and ceiling
     * models have distinct reference orientations.
     */
    private static final Vec3 V_T1 = voxelOf(3.5, 2.5, 8), V_T2 = voxelOf(12.5, 2.5, 8);
    private static final Vec3 V_T1R = voxelOf(8, 2.5, 3.5), V_T2R = voxelOf(8, 2.5, 12.5);
    private static final Vec3 H_T1 = voxelOf(13.5, 3.5, 8), H_T2 = voxelOf(13.5, 12.5, 8);
    private static final Vec3 H_T1R = voxelOf(13.5, 8, 12.5), H_T2R = voxelOf(13.5, 8, 3.5);

    private static Vec3 voxelOf(double x, double y, double z) {
        return new Vec3(x / 16f, y / 16f, z / 16f);
    }

    private static Vec3 nodeOffset(Direction facing, boolean roll, int id) {
        boolean first = id == 0;
        return switch (facing) {
            case UP -> first ? (roll ? V_T1R : V_T1) : (roll ? V_T2R : V_T2);
            case DOWN -> first ? (roll ? voxelOf(8, 13.5, 3.5) : voxelOf(3.5, 13.5, 8))
                               : (roll ? voxelOf(8, 13.5, 12.5) : voxelOf(12.5, 13.5, 8));
            case WEST -> first ? (roll ? H_T1R : H_T1) : (roll ? H_T2R : H_T2);
            case EAST -> first ? (roll ? voxelOf(2.5, 8, 3.5) : voxelOf(2.5, 3.5, 8))
                               : (roll ? voxelOf(2.5, 8, 12.5) : voxelOf(2.5, 12.5, 8));
            case NORTH -> first ? (roll ? voxelOf(12.5, 8, 13.5) : voxelOf(8, 3.5, 13.5))
                                : (roll ? voxelOf(3.5, 8, 13.5) : voxelOf(8, 12.5, 13.5));
            case SOUTH -> first ? (roll ? voxelOf(3.5, 8, 2.5) : voxelOf(8, 3.5, 2.5))
                                : (roll ? voxelOf(12.5, 8, 2.5) : voxelOf(8, 12.5, 2.5));
        };
    }

    /** Add the block origin to the local terminal offset for callers that need world coordinates. */
    public static Vec3 absoluteNodePosition(BlockPos pos, BlockState state, int id) {
        return nodeOffset(state.getValue(FACING), state.getValue(ROLL), id)
                .add(pos.getX(), pos.getY(), pos.getZ());
    }

    public static Map<Integer, Vec3> absoluteNodePositions(BlockPos pos, BlockState state) {
        Map<Integer, Vec3> map = new java.util.HashMap<>();
        for (int id = 0; id < 2; id++)
            map.put(id, absoluteNodePosition(pos, state, id));
        return map;
    }

    /** CEE adds the block position to these local terminal offsets. */
    @Override
    public Map<Integer, Vec3> getNodePositions(Level level, BlockPos pos, BlockState state) {
        Map<Integer, Vec3> map = new java.util.HashMap<>();
        for (int id = 0; id < 2; id++)
            map.put(id, nodeOffset(state.getValue(FACING), state.getValue(ROLL), id));
        return map;
    }

    @Override
    public Vec3 getNodePosition(Level level, BlockPos pos, BlockState state, int id) {
        return nodeOffset(state.getValue(FACING), state.getValue(ROLL), id);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!player.getMainHandItem().isEmpty())
            return InteractionResult.PASS;
        return onBlockEntityUse(level, pos, be ->
                be.replaceBulb(player, InteractionHand.MAIN_HAND, ItemStack.EMPTY)
                        ? InteractionResult.SUCCESS
                        : InteractionResult.FAIL);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (hand != InteractionHand.MAIN_HAND)
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (stack.getItem() instanceof ILightBulb) {
            return onBlockEntityUseItemOn(level, pos, be ->
                    be.replaceBulb(player, hand, stack)
                            ? ItemInteractionResult.SUCCESS
                            : ItemInteractionResult.FAIL);
        }
        if (stack.getItem() instanceof DyeItem dye) {
            return onBlockEntityUseItemOn(level, pos, be -> be.setColor(dye.getDyeColor()));
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        var be = params.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        if (be instanceof LightFixtureBlockEntity fixture) {
            ItemStack bulb = fixture.copyBulbStack();
            if (!bulb.isEmpty()) {
                var drops = new ArrayList<>(super.getDrops(state, params));
                drops.add(bulb);
                return drops;
            }
        }
        return super.getDrops(state, params);
    }

    @Override
    public Class<LightFixtureBlockEntity> getBlockEntityClass() {
        return LightFixtureBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends LightFixtureBlockEntity> getBlockEntityType() {
        return ModBlockEntityTypes.LIGHT_FIXTURE.get();
    }
}
