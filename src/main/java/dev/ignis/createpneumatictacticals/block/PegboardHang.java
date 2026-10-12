package dev.ignis.createpneumatictacticals.block;

import com.simibubi.create.content.contraptions.StructureTransform;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Vector3f;

/**
 * How a hung item is posed on a pegboard: which way its muzzle points and
 * where its bounding box meets the plate. Pure math, shared by the server
 * (which records the choice) and the client (which draws it and previews it).
 *
 * <p>Gun model convention (author geo, see {@code _receiver.geo.json}): the
 * muzzle is the model's {@code -Z} ({@code loc_barrel} at z = -3.5 px against
 * {@code loc_stock} at +4.5), {@code +Y} is up on the gun, {@code +X} the
 * thickness. A hang is described by an {@link Orient} — vertical/horizontal
 * plus a sign along the board's horizontal axis or Y, i.e. the side the
 * muzzle points to — and turned into the world rotation by
 * {@link #basis(Direction, Orient)}.
 */
public final class PegboardHang {

    /** {@code sign} = +1 means the muzzle runs along +{@code uAxis} (horizontal) or +Y (vertical) */
    public record Orient(boolean vertical, int sign) {
    }

    /**
     * The pose that points a muzzle along {@code direction}: the dominant of
     * "up/down" and "along the board" wins, ties going horizontal. Read off the
     * player's look when an item is placed, and off a moved muzzle when a
     * contraption carries the plate (see {@link #follow}).
     */
    public static Orient orient(Vec3 direction, Direction facing) {
        double u = facing.getAxis() == Direction.Axis.Z ? direction.x : direction.z;
        if (Math.abs(direction.y) > Math.abs(u)) {
            return new Orient(true, direction.y >= 0 ? 1 : -1);
        }
        return new Orient(false, u >= 0 ? 1 : -1);
    }

    /** the world direction a hang's muzzle points along, the negated image of the model's -Z */
    public static Vector3f muzzle(Direction facing, Orient orient) {
        return basis(facing, orient).getColumn(2, new Vector3f()).negate();
    }

    /** the board's outward normal (the side the item hangs on) */
    public static Vector3f normal(Direction facing) {
        return new Vector3f(facing.getStepX(), facing.getStepY(), facing.getStepZ());
    }

    /**
     * Model-to-world rotation: column {@code i} is the world image of model
     * axis {@code i} (X, Y, Z), right-handed ({@code ex = ey x ez}).
     *
     * <p>Horizontal: the muzzle runs along the board, the gun stays upright
     * (sights up). Vertical: the muzzle runs up/down and the gun's top faces
     * away from the plate (sights out).
     *
     * <p>The muzzle sits on the model's <b>-Z</b> (author convention: the
     * receiver's {@code loc_barrel} is at z = -3.5 px, {@code loc_stock} at
     * +4.5, and a barrel's cubes run to z = -9), so every muzzle direction
     * below is the NEGATED image of model +Z — that is what makes
     * {@code Orient.sign} read as "the side the muzzle points to".
     */
    public static Matrix3f basis(Direction facing, Orient orient) {
        Vector3f ey;
        Vector3f ez;
        if (orient.vertical()) {
            ez = new Vector3f(0f, -orient.sign(), 0f);
            ey = normal(facing);
        } else {
            Direction.Axis axis = PegboardPanel.uAxis(facing);
            ez = new Vector3f(axis == Direction.Axis.X ? -orient.sign() : 0f, 0f,
                    axis == Direction.Axis.Z ? -orient.sign() : 0f);
            ey = new Vector3f(0f, 1f, 0f);
        }
        Vector3f ex = new Vector3f(ey).cross(ez);
        return new Matrix3f().setColumn(0, ex).setColumn(1, ey).setColumn(2, ez);
    }

    /**
     * The same rotation as a quaternion ({@code PoseStack.mulPose} takes one,
     * not a matrix). Columns are unit and orthogonal, so the unnormalised
     * conversion is exact.
     */
    public static org.joml.Quaternionf rotation(Direction facing, Orient orient) {
        return basis(facing, orient).getUnnormalizedRotation(new org.joml.Quaternionf());
    }

    /**
     * The model-space point that must land on the board anchor: the bounding
     * box's centre along the two axes parallel to the plate, and the box's
     * NEAR end along the axis that points away from the plate — that face is
     * the item's back and rests on the board.
     *
     * <p>The away-from-plate axis is found from {@code basis} rather than a
     * hand-written per-facing table, so all four facings x both orientations
     * come out right by construction.
     *
     * @param bounds {@code [minX,minY,minZ,maxX,maxY,maxZ]}
     */
    public static Vector3f refPoint(float[] bounds, Direction facing, Orient orient) {
        Matrix3f basis = basis(facing, orient);
        Vector3f out = normal(facing);
        Vector3f ref = new Vector3f((bounds[0] + bounds[3]) / 2f, (bounds[1] + bounds[4]) / 2f,
                (bounds[2] + bounds[5]) / 2f);
        Vector3f column = new Vector3f();
        for (int axis = 0; axis < 3; axis++) {
            basis.getColumn(axis, column);
            float dot = column.dot(out);
            if (Math.abs(dot) < 0.9f) continue;
            // this model axis is the one pointing out of the plate: its near end touches the board
            float near = dot > 0 ? bounds[axis] : bounds[axis + 3];
            if (axis == 0) ref.x = near;
            else if (axis == 1) ref.y = near;
            else ref.z = near;
            break;
        }
        return ref;
    }

    /** where a hang sits after the plate moved: a hole, and the pose it takes there */
    public record Placement(int x, int y, Orient orient) {
    }

    /**
     * Where a hang lands when a contraption's disassembly hands the plate a
     * {@link StructureTransform}: the hole's world point and the muzzle's world
     * direction are pushed through the very transform Create used for the
     * structure, then read back in the frame of the state it re-places the plate
     * with.
     *
     * <p>{@code plate} is that re-placed state. Create places the moved block
     * (and with it the block entity holding the hangs) before handing out the
     * transform, so the frame the hangs are still recorded in is the one the
     * transform arrives from: recovered here by asking Create's own state
     * transform which facing lands on the plate's.
     *
     * <p>Reproducing the physical result beats a table of cases: mirrors and
     * quarter/half turns about all three axes differ in what they do to each
     * stored coordinate, while the transform itself already says it. Whole turns
     * and mirrors land the hang exactly; a quarter turn about X or Z would make
     * the plate a floor or ceiling board, which this block cannot be - Create
     * leaves it a wall board there - so the hang settles as near as a wall plate
     * can express, the muzzle taking the pose it points closest to.
     */
    public static Placement follow(StructureTransform transform, BlockState plate,
                                   int x, int y, boolean vertical, int sign) {
        Direction to = plate.getValue(WeaponPegboardBlock.FACING);
        Direction from = to;
        for (Direction candidate : Direction.Plane.HORIZONTAL) {
            if (transform.apply(plate.setValue(WeaponPegboardBlock.FACING, candidate))
                    .getValue(WeaponPegboardBlock.FACING) == to) {
                from = candidate;
                break;
            }
        }

        double[] px = PegboardGrid.toWorld(x, y, WeaponPegboardBlock.HANG_PLANE_PX, from);
        Vec3 anchor = transform.applyWithoutOffset(new Vec3(px[0] / 16, px[1] / 16, px[2] / 16));
        double[] local = PegboardGrid.toLocal(anchor.x * 16, anchor.y * 16, anchor.z * 16, to);
        Vector3f stored = muzzle(from, new Orient(vertical, sign));
        Vec3 moved = transform.applyWithoutOffsetUncentered(new Vec3(stored.x, stored.y, stored.z));
        return new Placement(PegboardGrid.snap(local[0]), PegboardGrid.snap(local[1]), orient(moved, to));
    }

    private PegboardHang() {
    }
}
