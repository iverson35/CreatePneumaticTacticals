package dev.ignis.createpneumatictacticals.block;

import dev.ignis.createpneumatictacticals.block.entity.GunWorkbenchBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Pneumatic gun assembly bench — fully 3D, no GUI. Right-click with a gun
 * or a receiver stages it (the client sends Workbench3dPacket; a receiver
 * auto-creates the bare gun server-side). The staged gun lies on the
 * bench (muzzle to the left of whoever faces its front) with [+]/[-]/[▼]
 * markers. The block entity persistently holds the
 * staged gun; breaking the block drops it (modules are baked into its NBT —
 * dropping both would duplicate them).
 */
public class GunWorkbenchBlock extends net.minecraft.world.level.block.HorizontalDirectionalBlock
        implements EntityBlock {

    /**
     * Collision/outline cubes (px) of the model's {@code main} group: the
     * tabletop and the four legs. The tabletop's model box is y 13.9..15.9;
     * nudged +0.1 the top face lands exactly on 16 px = 1 block, so entities
     * standing on the bench sit on the block grid. The 23x2x15 cloth is
     * decoration only and carries no collision.
     */
    private static final int[][] TABLE_CUBES = {
            {-4, 14, 0, 20, 16, 16},   // tabletop (13.9..15.9 + 0.1)
            {17, 0, 13, 19, 14, 15},   // legs
            {-3, 0, 13, -1, 14, 15},
            {-3, 0, 1, -1, 14, 3},
            {17, 0, 1, 19, 14, 3},
    };

    private static final java.util.Map<Direction, VoxelShape> SHAPES = buildShapes();

    private static java.util.Map<Direction, VoxelShape> buildShapes() {
        java.util.Map<Direction, VoxelShape> shapes = new java.util.EnumMap<>(Direction.class);
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            VoxelShape shape = Shapes.empty();
            for (int[] c : TABLE_CUBES) {
                int[] r = rotateCube(c, facing);
                shape = Shapes.or(shape, Block.box(r[0], r[1], r[2], r[3], r[4], r[5]));
            }
            shapes.put(facing, shape.optimize());
        }
        return shapes;
    }

    /**
     * Model-space to world cube rotation, matching the blockstate's y
     * rotation. Two things had to line up: the direction (checked against
     * the vanilla furnace mapping, facing=east yields y=90 and turns the
     * model's north side east) and the pivot — FaceBakery rotates variants
     * about the block CENTRE (0.5, 0.5, 0.5), not the block origin, so an
     * origin-based rotate would slide the shape a whole block off.
     * y=90 -> x' = 16 - z, z' = x;  y=180 -> 16 - both;  y=270 -> x' = z,
     * z' = 16 - x (px).
     */
    private static int[] rotateCube(int[] c, Direction facing) {
        return switch (facing) {
            case EAST -> new int[]{16 - c[5], c[1], c[0], 16 - c[2], c[4], c[3]};
            case SOUTH -> new int[]{16 - c[3], c[1], 16 - c[5], 16 - c[0], c[4], 16 - c[2]};
            case WEST -> new int[]{c[2], c[1], 16 - c[3], c[5], c[4], 16 - c[0]};
            default -> c; // NORTH: as authored
        };
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
                               CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    public GunWorkbenchBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, net.minecraft.core.Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(
            net.minecraft.world.level.block.state.StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
        // front faces the placer (furnace convention); the staged gun is laid
        // out relative to this facing
        return this.defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GunWorkbenchBlockEntity(pos, state);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide
                && level.getBlockEntity(pos) instanceof GunWorkbenchBlockEntity bench) {
            net.minecraft.world.item.ItemStack gun = bench.getGunSlot().getItem(0);
            if (!gun.isEmpty()) {
                // staged modules are baked into the gun's NBT — dropping both
                // would duplicate them
                net.minecraft.world.Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), gun);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /**
     * No-op on the server: staging is driven by Workbench3dPacket (the
     * client sends it after picking a marker hitbox). Consuming here keeps
     * vanilla from treating the click as item-use (bow draw, etc).
     */
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        return level.isClientSide ? InteractionResult.SUCCESS : InteractionResult.CONSUME;
    }
}