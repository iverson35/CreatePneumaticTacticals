package dev.ignis.createpneumatictacticals.block;

import dev.ignis.createpneumatictacticals.block.entity.WeaponPegboardBlockEntity;
import dev.ignis.createpneumatictacticals.item.GunItem;
import dev.ignis.createpneumatictacticals.item.ModuleItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Weapon pegboard (武器挂板): a 3 px thick plate, four horizontal facings,
 * that hangs guns and modules on a 4 px hole grid. The plate hugs the back of
 * its block, so it mounts against a wall with its front (and everything hung
 * on it) facing the room; right-click that front with
 * a gun or a module to hang it on the hole the crosshair points at (the same
 * hole refuses a second item); sneak-right-click takes the item nearest to
 * the click. Horizontal facing (furnace convention, the front faces the
 * placer); the collision shape follows the model, rotated per facing like
 * {@code GunWorkbenchBlock}.
 *
 * <p>Adjacent plates of the same facing merge into one rectangle
 * ({@link PegboardPanel}) and the connected texture drops the seams inside
 * it, so a wall of these reads as a single board. Hang contents live on the
 * block entity and are synced to the client, whose renderer draws them;
 * breaking the block drops the plate plus everything hung on it.
 */
public class WeaponPegboardBlock extends HorizontalDirectionalBlock implements EntityBlock {

    /**
     * Plate thickness (px). The plate's back sits on the block's FAR side, so
     * the board reads as a panel bolted to a wall with its front facing the
     * room, instead of the hanging face being the block boundary itself.
     */
    private static final int PLATE_THICKNESS = 3;

    /**
     * Model-space z (px) of the plate's front face — the plane a hung item's
     * back leaves from. Model +z runs from the room side (0) toward the wall
     * (16); {@link PegboardGrid#toWorld} and the blockstate's y rotation carry
     * that direction into every facing, so {@code 16 - thickness} is the front
     * face plane for all four.
     */
    public static final int HANG_PLANE_PX = 16 - PLATE_THICKNESS;

    /** sneak-take reach around the click point (px): under the 4 px hole spacing */
    public static final int TAKE_RADIUS_PX = 6;

    /**
     * Texture set of this plate. Materials restrict connection: see
     * {@code PegboardCTBehaviour}, which merges the panel only across plates
     * of the same material.
     */
    public static final EnumProperty<PegboardMaterial> MATERIAL =
            EnumProperty.create("material", PegboardMaterial.class);

    private static final int[][] PLATE_CUBES = {
            {0, 0, HANG_PLANE_PX, 16, 16, 16},
    };

    private static final Map<Direction, VoxelShape> SHAPES = buildShapes();

    private static Map<Direction, VoxelShape> buildShapes() {
        Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            VoxelShape shape = Shapes.empty();
            for (int[] c : PLATE_CUBES) {
                int[] r = rotateCube(c, facing);
                shape = Shapes.or(shape, Block.box(r[0], r[1], r[2], r[3], r[4], r[5]));
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

    public WeaponPegboardBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(MATERIAL, PegboardMaterial.WOOD));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, MATERIAL);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // front faces the placer (furnace convention)
        return this.defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WeaponPegboardBlockEntity(pos, state);
    }

    /** a gun, or a module item that resolves to a definition */
    public static boolean isHangable(ItemStack stack) {
        return stack.getItem() instanceof GunItem
                || (stack.getItem() instanceof ModuleItem module && module.isModule(stack));
    }

    private static void sound(Level level, BlockPos pos, SoundEvent event) {
        level.playSound(null, pos, event, SoundSource.BLOCKS, 0.8f, 0.8f);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        Direction facing = state.getValue(FACING);
        if (hit.getDirection() != facing) return InteractionResult.PASS;
        if (!(level.getBlockEntity(pos) instanceof WeaponPegboardBlockEntity board)) {
            return InteractionResult.PASS;
        }
        // the client always swings: every branch below is a server decision,
        // and PASS here would let the off-hand item act on the same click
        if (level.isClientSide) return InteractionResult.SUCCESS;
        int[] anchor = PegboardGrid.anchorOf(facing, hit);
        ItemStack held = player.getItemInHand(hand);

        // take back: sneak, nearest hang to the click point
        if (player.isShiftKeyDown()) {
            ItemStack taken = board.removeNearest(anchor[0], anchor[1], TAKE_RADIUS_PX);
            if (taken == null) return InteractionResult.PASS; // nothing hung here: let the click through
            if (!player.getInventory().add(taken)) player.drop(taken, false);
            sound(level, pos, SoundEvents.BUNDLE_REMOVE_ONE);
            return InteractionResult.CONSUME;
        }

        if (!isHangable(held)) return InteractionResult.PASS;
        // occupied hole: swallow the click, hang nothing
        if (board.occupied(anchor[0], anchor[1])) return InteractionResult.CONSUME;
        PegboardHang.Orient orient = PegboardHang.orient(player.getViewVector(1f), facing);
        ItemStack hung = held.copy();
        hung.setCount(1);
        if (!player.isCreative()) held.shrink(1);
        board.addHang(hung, anchor[0], anchor[1], orient.vertical(), orient.sign());
        sound(level, pos, SoundEvents.BUNDLE_INSERT);
        return InteractionResult.CONSUME;
    }

    /**
     * No loot table in this mod: the plate drops itself, plus every hang on
     * THIS block (each block entity owns only its own hangs). Four items share
     * this block, one per material, so the drop has to look the item up from
     * the state — {@code new ItemStack(this)} would always yield the last one
     * registered.
     */
    @Override
    @Deprecated
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder drops) {
        List<ItemStack> out = new ArrayList<>();
        out.add(new ItemStack(ModBlocks.itemFor(state.getValue(MATERIAL))));
        BlockEntity be = drops.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        if (be instanceof WeaponPegboardBlockEntity board) {
            for (WeaponPegboardBlockEntity.Hang hang : board.hangs()) {
                out.add(hang.stack().copy());
            }
        }
        return out;
    }

    /**
     * Creative break: vanilla drops nothing at all there, but whatever hangs on
     * the board is still the player's, so it falls anyway — only the plate
     * itself is skipped (it is free to re-place). Survival is untouched:
     * {@link #getDrops} hands out the plate and its contents there, so popping
     * them here as well would duplicate them.
     */
    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && player.isCreative()
                && level.getBlockEntity(pos) instanceof WeaponPegboardBlockEntity board) {
            for (ItemStack stack : board.takeAllHangs()) {
                popResource(level, pos, stack);
            }
        }
        super.playerWillDestroy(level, pos, state, player);
    }
}
