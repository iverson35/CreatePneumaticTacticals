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
 * <p>Adjacent pegboards of the same facing and material read as ONE plate: the
 * board runs to the block boundary across the borders inside the rectangle and
 * keeps the frame on the rest, so the shape of the rectangle is what the player
 * sees. The rectangle is the largest-area inscribed rectangle of the block's
 * connected cluster (a 4-connected blob in the {horizontal axis} x {Y} plane);
 * blocks that fall outside it draw as standalone 1x1 plates.
 *
 * <p>A cluster that fits within {@link #MAX_SPAN} x {@link #MAX_SPAN} merges
 * whole, wherever it sits. A bigger one is cut into {@link #MAX_SPAN}-wide
 * chunks on a world-aligned grid, and each chunk merges on its own — so a wall
 * tiles into boards with a frame at every {@link #MAX_SPAN}-block seam, instead
 * of every block falling back to 1x1. The grid is aligned to world coordinates
 * so both plates at a seam measure the same way and agree on the cut.
 *
 * <p>Pure math on block positions — no level, no block state — so the block
 * (which stamps its four borders from it) and the hang renderer measure the
 * same way.
 */
public final class PegboardPanel {

    /** boards are at most this wide and tall; bigger clusters are cut into this grid */
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
     * cluster, or {@code pos}'s own 1x1 panel when it falls outside that
     * rectangle.
     *
     * <p>The whole cluster is tried first, so the merge does not depend on where
     * the chunk grid happens to run. Only a cluster bigger than
     * {@link #MAX_SPAN} falls through to the world-aligned chunk that holds
     * {@code pos}.
     *
     * @param isBoard cluster membership; must accept {@code pos} itself
     */
    public static Panel panelOf(BlockPos pos, Direction facing, Predicate<BlockPos> isBoard) {
        Direction.Axis axis = uAxis(facing);
        Panel whole = inscribed(pos, facing, isBoard, axis,
                Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        if (whole != null) return whole;
        int u = u(pos, axis);
        int v = pos.getY();
        int cellU = Math.floorDiv(u, MAX_SPAN) * MAX_SPAN;
        int cellV = Math.floorDiv(v, MAX_SPAN) * MAX_SPAN;
        Panel chunk = inscribed(pos, facing, isBoard, axis,
                cellU, cellV, cellU + MAX_SPAN - 1, cellV + MAX_SPAN - 1);
        return chunk != null ? chunk : single(pos, facing);
    }

    /**
     * The largest inscribed rectangle of the cluster cells inside the window;
     * {@code null} when the cluster reaches past the window (which for the
     * unbounded window means it is bigger than {@link #MAX_SPAN} on an axis),
     * or when {@code pos} ends up outside the rectangle.
     */
    private static Panel inscribed(BlockPos pos, Direction facing, Predicate<BlockPos> isBoard, Direction.Axis axis,
                                   int windowU0, int windowV0, int windowU1, int windowV1) {
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
                if (nu < windowU0 || nu > windowU1 || nv < windowV0 || nv > windowV1) continue;
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
                // only an unbounded window can outgrow MAX_SPAN
                if (maxU - minU + 1 > MAX_SPAN || maxV - minV + 1 > MAX_SPAN) return null;
                queue.add(new long[]{nu, nv});
            }
        }

        int spanU = maxU - minU + 1;
        int spanV = maxV - minV + 1;
        // 64 cells and at most 64*... candidate rectangles: cheap enough per call
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
        return best != null && best.contains(startU, startV) ? best : null;
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
