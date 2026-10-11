package dev.ignis.createpneumatictacticals.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Geometry truth of the weapon-module machine tool: model space &harr; world
 * space. Both the renderer and every hit test MUST go through this class — one
 * shared mapping means the interaction regions can never drift away from the
 * drawn model, no matter which way the two-cell structure ends up oriented.
 *
 * <h2>Model space</h2>
 * The geo file is authored in Blockbench units (16 px = 1 block): the machine
 * is 16 px wide (x -8..7.75), 16.75 px tall (y 0..16.75) and 32 px long
 * (z -24..8), so it spans exactly two block cells along model z. The MASTER
 * block (this mod's block, holding the block entity) covers model z -8..+8 —
 * model (x, z) = (0, 0) is the centre of the master cell — and the EXTENSION
 * block covers model z -24..-8.
 *
 * <h2>Facing</h2>
 * The machine's front (the raised panel carrying the screen plate at
 * {@code loc_screen}, and the sliding door) is the model's <b>+X</b> face.
 * GeckoLib mirrors x at bake time ({@code BakedModelFactory} negates cube
 * origins and bone pivots, {@code RenderUtils.translateMatrixToBone} negates
 * {@code posX}), so the front reaches the screen as pose-local <b>-X</b>.
 * Yaw must therefore satisfy {@code R_y(θ)·(-1,0,0) = facing}, i.e.
 * {@code cos θ = -stepX} and {@code sin θ = stepZ}: θ = atan2(stepZ, -stepX).
 * Model +Z (unmirrored — {@code translateMatrixToBone} keeps its sign) then
 * lands on {@code R_y(θ)·(0,0,1) = (stepZ, 0, -stepX)}, which is exactly
 * {@link Direction#getCounterClockWise()}.
 */
public final class MachineLayout {

    /** model +Z in world space: the direction the machine's length runs, extension last */
    private static Direction modelPlusZ(Direction facing) {
        return facing.getCounterClockWise();
    }

    /** block state facing of either half; both blocks carry FACING */
    public static Direction facing(BlockState state) {
        return state.getValue(HorizontalDirectionalBlock.FACING);
    }

    /** pose yaw (radians) putting the machine's front — pose-local -X — on {@code facing} */
    public static float yawRadians(Direction facing) {
        return (float) Math.atan2(facing.getStepZ(), -facing.getStepX());
    }

    /** the extension half's position for a master at {@code master} */
    public static BlockPos slavePos(BlockPos master, Direction facing) {
        return master.relative(modelPlusZ(facing).getOpposite());
    }

    /** the master half's position for an extension at {@code slave} */
    public static BlockPos masterPos(BlockPos slave, Direction facing) {
        return slave.relative(modelPlusZ(facing));
    }

    /**
     * Master position of whichever half {@code hitPos} is: the block entity,
     * the recipe and the door state all live on the master, so both halves'
     * right-clicks resolve to it.
     */
    public static BlockPos masterOf(BlockPos hitPos, BlockState state) {
        return state.is(ModBlocks.WEAPON_MODULE_MACHINE_TOOL.get())
                ? hitPos
                : masterPos(hitPos, facing(state));
    }

    /** model-space z of a hit relative to a master with this facing, in pixels (0 = master cell centre) */
    public static double modelZPx(BlockPos master, Direction facing, Vec3 hit) {
        Direction plusZ = modelPlusZ(facing);
        return 16.0 * ((hit.x - (master.getX() + 0.5)) * plusZ.getStepX()
                + (hit.z - (master.getZ() + 0.5)) * plusZ.getStepZ());
    }

    /** model-space y of a hit, in pixels (0 = block floor) */
    public static double modelYPx(BlockPos master, Vec3 hit) {
        return 16.0 * (hit.y - master.getY());
    }

    /** the machine's model height: 16.75 px, a hair above the cells it stands in */
    public static final double MODEL_HEIGHT = 16.75 / 16.0;

    /**
     * The whole machine's box in the local space of ONE half: both cells along
     * the machine's length, one cell across, at the model's true height.
     *
     * <p>Because the box reaches past the block it belongs to, a ray that
     * touches either cell reports a hit on the block it entered first — and
     * {@code LevelRenderer} draws the black outline from that hit block's
     * shape, so both halves returning the same two-cell region means the
     * selection frame always frames the whole machine instead of whichever
     * sub-block the crosshair landed on.
     *
     * <p>The box must cover the two cells the machine really stands in: the
     * half's own cell [0,1] and the other half's cell one step toward it
     * ({@code d} below) — a box built from the wrong end would frame one cell
     * of the machine plus one cell of empty air.
     *
     * <p>Only {@code getShape} (outline + ray tracing) uses this. Collision is
     * deliberately left a full cube per cell: the player walks into the
     * machine's two cells exactly as before.
     *
     * @param masterHalf the master covers its own cell and the extension's
     *                   (which sits at {@code +d}), the extension its own cell
     *                   and the master's (which sits at {@code -d})
     */
    public static VoxelShape machineShape(BlockState state, boolean masterHalf) {
        // d: master -> extension, in block-local axis terms
        Direction d = modelPlusZ(facing(state)).getOpposite();
        int step = d.getStepX() != 0 ? d.getStepX() : d.getStepZ();
        // where the other half's cell is, in this half's own cell coordinates:
        // one step along d from the master, one step against it from the extension
        int other = masterHalf ? step : -step;
        double lo = Math.min(0.0, other);
        double hi = Math.max(1.0, other + 1.0);
        return d.getStepX() != 0
                ? Shapes.box(lo, 0.0, 0.0, hi, MODEL_HEIGHT, 1.0)
                : Shapes.box(0.0, 0.0, lo, 1.0, MODEL_HEIGHT, hi);
    }

    /** drawn screen plate: 4 px wide (z 0..4), 6 px tall (y 5.25..11.25), 1 px of slack in z */
    public static final double SCREEN_Z_MIN = -1.0, SCREEN_Z_MAX = 4.0;
    public static final double SCREEN_Y_MIN = 5.25, SCREEN_Y_MAX = 11.25;

    /**
     * Is this hit on the screen plate? Only the front face counts, and only
     * the rectangle the screen is drawn in — so the rest of the front face
     * keeps working the door, exactly like the model suggests.
     */
    public static boolean isScreenHit(BlockState masterState, BlockPos master, BlockHitResult hit) {
        Direction facing = facing(masterState);
        if (hit.getDirection() != facing) return false;
        Vec3 loc = hit.getLocation();
        double z = modelZPx(master, facing, loc);
        double y = modelYPx(master, loc);
        return z >= SCREEN_Z_MIN && z <= SCREEN_Z_MAX && y >= SCREEN_Y_MIN && y <= SCREEN_Y_MAX;
    }

    private MachineLayout() {
    }
}
