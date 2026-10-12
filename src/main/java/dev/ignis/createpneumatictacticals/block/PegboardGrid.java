package dev.ignis.createpneumatictacticals.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The pegboard's hole grid and its model-space to block-space mapping.
 *
 * <p>Holes sit at 2/6/10/14 px on both plate axes (4 px apart, 16 per plate);
 * an item is anchored to the nearest one. The mapping matches the blockstate's
 * {@code y} rotation, because the rotation is what turns the authored north
 * facing model into the other three — so a click on ANY facing yields the
 * model-space anchor the renderer can feed straight back through
 * {@link #toWorld}.
 *
 * <p>Deliberately free of the {@code Block} class (this type is loaded by the
 * block entity and the renderer, and its math is testable on its own).
 */
public final class PegboardGrid {

    /** hole centres (px) inside the plate: 2/6/10/14 on both axes */
    public static final int HOLE_MIN = 2;
    public static final int HOLE_MAX = 14;
    public static final int HOLE_STEP = 4;

    /**
     * Model-space px to block-space px, the same mapping the blockstate's
     * {@code y} rotation applies: north keeps its axes, east is
     * {@code (x, z) -> (16 - z, x)}, south {@code (16 - x, 16 - z)}, west
     * {@code (z, 16 - x)}; y never changes.
     */
    public static double[] toWorld(double x, double y, double z, Direction facing) {
        return switch (facing) {
            case EAST -> new double[]{16 - z, y, x};
            case SOUTH -> new double[]{16 - x, y, 16 - z};
            case WEST -> new double[]{z, y, 16 - x};
            default -> new double[]{x, y, z}; // NORTH: as authored
        };
    }

    /** the inverse of {@link #toWorld} */
    public static double[] toLocal(double x, double y, double z, Direction facing) {
        return switch (facing) {
            case EAST -> new double[]{z, y, 16 - x};
            case SOUTH -> new double[]{16 - x, y, 16 - z};
            case WEST -> new double[]{16 - z, y, x};
            default -> new double[]{x, y, z};
        };
    }

    /** quantises a model-space px coordinate onto the hole grid */
    public static int snap(double px) {
        return Math.min(HOLE_MAX, Math.max(HOLE_MIN,
                HOLE_MIN + (int) Math.round((px - HOLE_MIN) / (double) HOLE_STEP) * HOLE_STEP));
    }

    /**
     * The hole a front-face hit lands on, as a model-space anchor
     * {@code {x, y}} in px ({@link #HOLE_MIN}..{@link #HOLE_MAX}).
     */
    public static int[] anchorOf(Direction facing, BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        Vec3 rel = hit.getLocation().subtract(pos.getX(), pos.getY(), pos.getZ());
        double[] local = toLocal(rel.x * 16, rel.y * 16, rel.z * 16, facing);
        return new int[]{snap(local[0]), snap(local[1])};
    }

    private PegboardGrid() {
    }
}
