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
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
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
 * <p>Adjacent plates of the same facing and material merge into one rectangle
 * ({@link PegboardPanel}); the block carries the four borders that rectangle
 * runs on across ({@link #LEFT}/{@link #RIGHT}/{@link #UP}/{@link #DOWN}) and
 * its model grows the board to the block boundary on those, so a wall of these
 * reads as a single board (the models come from
 * {@code tools/make_pegboard_models.py}). Hang contents live on the block
 * entity and are synced to the client, whose renderer draws them; breaking the
 * block drops the plate plus everything hung on it.
 */
public class WeaponPegboardBlock extends HorizontalDirectionalBlock implements EntityBlock {

    /**
     * Plate thickness (px). The plate's back sits on the block's FAR side, so
     * the board reads as a panel bolted to a wall with its front facing the
     * room, instead of the hanging face being the block boundary itself.
     */
    private static final int PLATE_THICKNESS = 3;

    /**
     * How far the patterned board is raised off the plain plate (px). The board
     * is a slab whose front covers the whole face at
     * {@link #HANG_PLANE_PX}: it runs to the block boundary on every border the
     * merged rectangle runs on across, and stops 1 px short on every other
     * one, where that groove shows the plate's frame at the bottom — the
     * per-state models {@code tools/make_pegboard_models.py} writes.
     */
    private static final double BOARD_RAISE_PX = 0.5;

    /**
     * Model-space z (px) of the raised board's front face — the plane a hung
     * item's back leaves from. Model +z runs from the room side (0) toward the
     * wall (16); {@link PegboardGrid#toWorld} and the blockstate's y rotation
     * carry that direction into every facing, so the plate's front minus the
     * raise is the right plane for all four.
     */
    public static final double HANG_PLANE_PX = 16 - PLATE_THICKNESS - BOARD_RAISE_PX;

    /** sneak-take reach around the click point (px): under the 4 px hole spacing */
    public static final int TAKE_RADIUS_PX = 6;

    /**
     * Texture set of this plate. Materials restrict connection: only plates of
     * the same material (and the same facing) share a {@link PegboardPanel}, so
     * a mixed wall keeps its boards separate and each material keeps its own
     * frame.
     */
    public static final EnumProperty<PegboardMaterial> MATERIAL =
            EnumProperty.create("material", PegboardMaterial.class);

    /**
     * The four borders the panel runs on across, as the model authors them with
     * the plate facing north: {@code left} is the model x = 16 border — the
     * sprite's u = 0 one, on the viewer's left when they face the plate's front
     * — {@code right} the x = 0 one, {@code up} y = 16 and {@code down} y = 0.
     * {@link #withConnections} sets them from the merged rectangle, so on the
     * other three facings they follow the model: {@code left} is the neighbour
     * at {@code FACING.getClockWise()}.
     */
    public static final BooleanProperty LEFT = BooleanProperty.create("left");
    public static final BooleanProperty RIGHT = BooleanProperty.create("right");
    public static final BooleanProperty UP = BooleanProperty.create("up");
    public static final BooleanProperty DOWN = BooleanProperty.create("down");

    /** one box covering plate and raised board: their union is flush in x and y */
    private static final double[][] PLATE_CUBES = {
            {0, 0, HANG_PLANE_PX, 16, 16, 16},
    };

    private static final Map<Direction, VoxelShape> SHAPES = buildShapes();

    private static Map<Direction, VoxelShape> buildShapes() {
        Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            VoxelShape shape = Shapes.empty();
            for (double[] c : PLATE_CUBES) {
                double[] r = rotateCube(c, facing);
                shape = Shapes.or(shape, Block.box(r[0], r[1], r[2], r[3], r[4], r[5]));
            }
            shapes.put(facing, shape.optimize());
        }
        return shapes;
    }

    /** same origin-vs-centre rotation as GunWorkbenchBlock.rotateCube */
    private static double[] rotateCube(double[] c, Direction facing) {
        return switch (facing) {
            case EAST -> new double[]{16 - c[5], c[1], c[0], 16 - c[2], c[4], c[3]};
            case SOUTH -> new double[]{16 - c[3], c[1], 16 - c[5], 16 - c[0], c[4], 16 - c[2]};
            case WEST -> new double[]{c[2], c[1], 16 - c[3], c[5], c[4], 16 - c[0]};
            default -> c; // NORTH: as authored
        };
    }

    public WeaponPegboardBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(MATERIAL, PegboardMaterial.WOOD)
                .setValue(LEFT, false)
                .setValue(RIGHT, false)
                .setValue(UP, false)
                .setValue(DOWN, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, MATERIAL, LEFT, RIGHT, UP, DOWN);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // front faces the placer (furnace convention), and the board already
        // sizes itself per border: at placement time the neighbours are what
        // this plate connects to, this cell being the one it will occupy
        return withConnections(this.defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite()), context.getLevel(),
                context.getClickedPos());
    }

    /**
     * Recomputes the four borders from the merged rectangle: a border runs on
     * when the cell across it falls inside {@link PegboardPanel#panelOf}, which
     * only ever holds cells of this plate's own facing and material. Boards of
     * other materials or facings, and same-facing plates outside the rectangle,
     * keep the frame between them.
     */
    private static BlockState withConnections(BlockState state, BlockGetter level, BlockPos pos) {
        Direction facing = state.getValue(FACING);
        PegboardMaterial material = state.getValue(MATERIAL);
        PegboardPanel.Panel panel = PegboardPanel.panelOf(pos, facing, candidate -> {
            BlockState candidateState = level.getBlockState(candidate);
            return candidateState.getBlock() instanceof WeaponPegboardBlock
                    && candidateState.getValue(FACING) == facing
                    && candidateState.getValue(MATERIAL) == material;
        });
        Direction.Axis uAxis = PegboardPanel.uAxis(facing);
        // the model's own four borders, in world directions: x = 16 is the
        // clockwise one, x = 0 counter-clockwise, and y is y everywhere
        return state
                .setValue(LEFT, runsOn(panel, uAxis, pos, facing.getClockWise()))
                .setValue(RIGHT, runsOn(panel, uAxis, pos, facing.getCounterClockWise()))
                .setValue(UP, runsOn(panel, uAxis, pos, Direction.UP))
                .setValue(DOWN, runsOn(panel, uAxis, pos, Direction.DOWN));
    }

    /** does the panel reach across {@code side} into the neighbouring cell */
    private static boolean runsOn(PegboardPanel.Panel panel, Direction.Axis uAxis, BlockPos pos, Direction side) {
        BlockPos neighbour = pos.relative(side);
        return panel.contains(PegboardPanel.u(neighbour, uAxis), neighbour.getY());
    }

    /**
     * A neighbour changed: its plate may have joined or left this plate's
     * merged rectangle, which is what the four borders say. Only this block's
     * state can change — a border flag never changes the rectangle — so the
     * update does not have to tell anyone but the client. Server side only: the
     * state is authoritative and arrives with the chunk on the client.
     */
    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbour, BlockPos neighbourPos,
                                boolean isMoving) {
        if (level.isClientSide) return;
        BlockState connected = withConnections(state, level, pos);
        if (connected != state) {
            level.setBlock(pos, connected, 3);
        }
        super.neighborChanged(state, level, pos, neighbour, neighbourPos, isMoving);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighbourState,
                                  LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        return level.isClientSide() ? state : withConnections(state, level, pos);
    }

    /**
     * Placement the placer did not drive — {@code /setblock}, a schematic, a
     * structure — arrives with the property defaults, so the borders are
     * recomputed here for every placement that is not already right.
     */
    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (level.isClientSide) return;
        BlockState connected = withConnections(state, level, pos);
        if (connected != state) {
            level.setBlock(pos, connected, 3);
        }
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
