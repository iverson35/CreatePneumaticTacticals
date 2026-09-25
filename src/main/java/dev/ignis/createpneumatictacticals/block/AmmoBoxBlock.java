package dev.ignis.createpneumatictacticals.block;

import dev.ignis.createpneumatictacticals.block.entity.AmmoBoxBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Ammo box: a UI-less 512-round single-type pod magazine. Items move by hand
 * (right-click: insert held stack / pull one group / Shift-pull one round)
 * or by automation — the block entity answers the Container interface, so
 * hoppers above drain it and hoppers beside/below feed it. Breaking keeps
 * the content on the dropped item (shulker-style, via getDrops). Horizontal
 * facing (furnace convention, front faces the placer); the collision shape
 * follows the model, rotated per facing like GunWorkbenchBlock.
 */
public class AmmoBoxBlock extends HorizontalDirectionalBlock
        implements net.minecraft.world.level.block.EntityBlock {

    /**
     * One box over the whole model bbox ([5,0,2] -&gt; [11,11,15]): the lid is
     * modeled tilted open, so a per-cube lid shape would float a level box in
     * the air — a single box covering its height reads upright instead.
     */
    private static final int[][] CUBES = {
            {5, 0, 2, 11, 11, 15},
    };

    private static final java.util.Map<Direction, VoxelShape> SHAPES = buildShapes();

    private static java.util.Map<Direction, VoxelShape> buildShapes() {
        java.util.Map<Direction, VoxelShape> shapes = new java.util.EnumMap<>(Direction.class);
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            VoxelShape shape = net.minecraft.world.phys.shapes.Shapes.empty();
            for (int[] c : CUBES) {
                int[] r = rotateCube(c, facing);
                shape = net.minecraft.world.phys.shapes.Shapes.or(shape,
                        Block.box(r[0], r[1], r[2], r[3], r[4], r[5]));
            }
            shapes.put(facing, shape.optimize());
        }
        return shapes;
    }

    /** same origin-vs-centre rotation as GunWorkbenchBlock.rotateCube */
    private static int[] rotateCube(int[] c, Direction facing) {
        return switch (facing) {
            case EAST -> new int[]{16 - c[5], c[1], c[0], 16 - c[2], c[4], c[3]};
            case SOUTH -> new int[]{16 - c[3], c[1], 16 - c[5], 16 - c[0], c[4], 16 - c[2]};
            case WEST -> new int[]{c[2], c[1], 16 - c[3], c[5], c[4], 16 - c[0]};
            default -> c; // NORTH: as authored
        };
    }

    public AmmoBoxBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
        // front faces the placer (furnace convention)
        return this.defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
                               CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    @Override
    public net.minecraft.world.level.block.RenderShape getRenderShape(BlockState state) {
        return net.minecraft.world.level.block.RenderShape.MODEL;
    }

    @Nullable
    @Override
    public net.minecraft.world.level.block.entity.BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AmmoBoxBlockEntity(pos, state);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.getBlockEntity(pos) instanceof AmmoBoxBlockEntity box) {
            return AmmoBoxBlockEntity.interact(player, hand, box);
        }
        return InteractionResult.PASS;
    }

    @Override
    @Deprecated
    public java.util.List<ItemStack> getDrops(BlockState state, net.minecraft.world.level.storage.loot.LootParams.Builder drops) {
        net.minecraft.world.level.block.entity.BlockEntity be = drops.getOptionalParameter(
                net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_ENTITY);
        if (be instanceof AmmoBoxBlockEntity box && !box.isEmpty()) {
            ItemStack drop = new ItemStack(this);
            be.saveToItem(drop);
            return java.util.List.of(drop);
        }
        return java.util.List.of(new ItemStack(this));
    }
}
