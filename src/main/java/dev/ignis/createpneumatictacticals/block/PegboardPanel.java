package dev.ignis.createpneumatictacticals.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The merged rectangle a pegboard block belongs to.
 *
 * <p>Adjacent pegboards of the same facing read as ONE plate: the connected
 * texture removes the seams inside the rectangle and keeps the frame on its
 * outer edges, so the shape of the rectangle is what the player sees. The
 * rectangle is the largest-area inscribed rectangle of the block's connected
 * cluster (a 4-connected blob in the {horizontal axis} x {Y} plane); blocks
 * that fall outside it draw as standalone 1x1 plates.
 *
 * <p>Pure math on block positions — no level, no block state — so the block,
 * its renderer and the connected-texture behaviour all measure the same way.
 * The cluster is at most {@link #MAX_SPAN} x {@link #MAX_SPAN} cells; anything
 * bigger is left unmerged (every block 1x1) rather than picking a big
 * rectangle out of a wall-sized sprawl.
 */
public final class PegboardPanel {

    /** clusters wider or taller than this never merge */
    public static final int MAX_SPAN = 8;

    /** an inclusive rectangle in board coordinates: u = horizontal axis, v = Y */
    public record Panel(int u0, int v0, int u1, int v1) {

        public boolean contains(int u, int v) {
            return u >= u0 && u <= u1 && v >= v0 && v <= v1;
        }

        public int area() {
            return (u1 - u0 + 1) * (v1 - v0 + 1);
        }
    }

    /** the board's horizontal axis: a plate facing along Z runs along X, and vice versa */
    public static Direction.Axis uAxis(Direction facing) {
        return facing.getAxis() == Direction.Axis.Z ? Direction.Axis.X : Direction.Axis.Z;
    }

    /** a block's coordinate on the board's horizontal axis */
    public static int u(BlockPos pos, Direction.Axis axis) {
        return axis == Direction.Axis.X ? pos.getX() : pos.getZ();
    }

    /** the single block's own 1x1 panel */
    public static Panel single(BlockPos pos, Direction facing) {
        int u = u(pos, uAxis(facing));
        int v = pos.getY();
        return new Panel(u, v, u, v);
    }

    /**
     * The panel {@code pos} belongs to: the largest inscribed rectangle of its
     * cluster, or {@code pos}'s own 1x1 panel when it is outside that rectangle
     * — or when the cluster is larger than {@link #MAX_SPAN} on either axis.
     *
     * @param isBoard cluster membership; must accept {@code pos} itself
     */
    public static Panel panelOf(BlockPos pos, Direction facing, Predicate<BlockPos> isBoard) {
        Direction.Axis axis = uAxis(facing);
        // the axis perpendicular to the board plane: every cluster cell shares it
        Direction.Axis depth = facing.getAxis();
        int depthCoord = depth == Direction.Axis.X ? pos.getX() : pos.getZ();
        int startU = u(pos, axis);
        int startV = pos.getY();

        Set<Long> cells = new HashSet<>();
        ArrayDeque<long[]> queue = new ArrayDeque<>();
        int minU = startU, maxU = startU, minV = startV, maxV = startV;
        cells.add(key(startU, startV));
        queue.add(new long[]{startU, startV});
        while (!queue.isEmpty()) {
            long[] cell = queue.poll();
            for (int[] step : STEPS) {
                int nu = (int) cell[0] + step[0];
                int nv = (int) cell[1] + step[1];
                if (!cells.add(key(nu, nv))) continue;
                BlockPos next = axis == Direction.Axis.X
                        ? new BlockPos(nu, nv, depthCoord)
                        : new BlockPos(depthCoord, nv, nu);
                if (!isBoard.test(next)) {
                    cells.remove(key(nu, nv));
                    continue;
                }
                if (nu < minU) minU = nu;
                if (nu > maxU) maxU = nu;
                if (nv < minV) minV = nv;
                if (nv > maxV) maxV = nv;
                if (maxU - minU + 1 > MAX_SPAN || maxV - minV + 1 > MAX_SPAN) {
                    return single(pos, facing);
                }
                queue.add(new long[]{nu, nv});
            }
        }

        int spanU = maxU - minU + 1;
        int spanV = maxV - minV + 1;
        // 512 candidate rectangles at most: cheap enough to measure per call
        boolean[][] solid = new boolean[spanU][spanV];
        for (long cell : cells) {
            int u = (int) (cell >> 32);
            int v = (int) cell;
            solid[u - minU][v - minV] = true;
        }
        Panel best = null;
        for (int i = 0; i < spanU; i++) {
            for (int j = 0; j < spanV; j++) {
                if (!solid[i][j]) continue;
                for (int w = 1; i + w <= spanU && solid[i + w - 1][j]; w++) {
                    for (int h = 1; j + h <= spanV; h++) {
                        boolean row = true;
                        for (int k = 0; k < w; k++) {
                            if (!solid[i + k][j + h - 1]) {
                                row = false;
                                break;
                            }
                        }
                        if (!row) break;
                        Panel candidate = new Panel(minU + i, minV + j,
                                minU + i + w - 1, minV + j + h - 1);
                        if (better(candidate, best)) best = candidate;
                    }
                }
            }
        }
        return best != null && best.contains(startU, startV) ? best : single(pos, facing);
    }

    /** area first (largest), then topmost, then leftmost, then narrowest */
    private static boolean better(Panel candidate, Panel best) {
        if (best == null) return true;
        if (candidate.area() != best.area()) return candidate.area() > best.area();
        if (candidate.v0() != best.v0()) return candidate.v0() < best.v0();
        if (candidate.u0() != best.u0()) return candidate.u0() < best.u0();
        return candidate.u1() - candidate.u0() < best.u1() - best.u0();
    }

    private static long key(int u, int v) {
        return ((long) u << 32) | (v & 0xFFFFFFFFL);
    }

    private static final int[][] STEPS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private PegboardPanel() {
    }
}
