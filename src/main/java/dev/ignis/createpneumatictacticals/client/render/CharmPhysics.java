package dev.ignis.createpneumatictacticals.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import dev.ignis.createpneumatictacticals.Config;
import dev.ignis.createpneumatictacticals.module.ModuleDefinition;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.slf4j.Logger;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;

/**
 * First-person swing simulation for charm modules (plan_v4).
 *
 * <p>A charm is a bracket, a chain and a pendant. The chain is whatever the
 * model ships: {@code chain_0} .. {@code chain_(N-1)} are probed until one is
 * missing, and a model with no chain bones at all falls back to a single
 * joint — the {@code pendant} bone hinged at its own pivot, its tip marked by
 * {@code loc_mass}. The chain runs as a Verlet point chain in the charm's OWN frame:
 * the root is pinned to the mount and never moves, gravity arrives as world
 * down rotated into that frame ({@link #downLocal}), and the frame's own
 * acceleration is injected as the pseudo-force of a non-inertial frame.
 *
 * <p>A world-space chain cannot work at this scale. Walking moves the mount
 * 0.033 blocks per 1/120s step — more than the first link is long — while
 * gravity contributes 0.0011 blocks per step, so the pin's teleport swamps it
 * and the chain is dragged into a fold instead of hanging. Simulating in the
 * charm's frame deletes that term entirely and leaves the chain's own dynamics
 * to dominate, which is what makes it swing and settle.
 *
 * <p>Collision is what the authored frame is for: local +Y is the mounting
 * surface's outward normal and y=0 is the receiver surface, so "outside the
 * receiver" is exactly {@code y >= surface_margin} — a plane that never moves
 * in this frame. The swing cone about {@link #downLocal} is the second clamp.
 * Both are solved INSIDE the distance iterations: clamping a point after the
 * distances have converged stretches its links and the next step pulls them
 * back, a two-way fight that reads as weightless drift.
 *
 * <p>Bones are driven per link: the link's absolute rotation is the minimal arc
 * from its rest direction to the direction to the next point, and the bone gets
 * the parent-relative part {@code R(i-1)^-1 · R(i)} as ZYX Euler angles — the
 * order GeckoLib applies them in ({@code RenderUtils.rotateMatrixAroundBone}
 * composes {@code Rz·Ry·Rx}, which is exactly {@code getEulerAnglesZYX}'s
 * inverse).
 *
 * <p>World-side math is double precision: a float round trip of an absolute
 * world position quantizes at ~1mm by x=10000, which is visible on a 20cm
 * charm. The bone-side math stays float — those vectors are charm-local.
 *
 * <p>Third person, the inventory icon and the workbench do not simulate: the
 * bones keep their authored rest pose.
 */
public final class CharmPhysics {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** most chain links a model may ship before the probe gives up */
    private static final int MAX_SEGMENTS = 16;
    /** simulated points for the current model: the pinned root plus one per
     *  link, the last of which is the center of mass */
    private static int points;
    /** bone marking the pendant's center of mass inside the charm model */
    private static final String MASS_BONE = "loc_mass";
    /** the pendant bone: charm models hang it under the deepest chain link
     *  (or straight off the body when they ship no chain); GunModulesLayer
     *  resolves it for the hidden-charm skin swap */
    static final String PENDANT_BONE = "pendant";

    /** fixed simulation timestep: a stable step regardless of frame rate */
    private static final double STEP_SECONDS = 1.0 / 120.0;
    private static final double STEP_TICKS = STEP_SECONDS * 20.0;
    /**
     * Constraint iterations per step. Sweeping root-outward propagates the pin
     * in one pass, but Gauss-Seidel is not exact even for bare links, and the
     * receiver clamps make it slower still: measured over-stretch is 53% at 4
     * iterations and 1.6% at 32, for ~10k flops per step.
     */
    private static final int ITERATIONS = 32;
    /** catch-up cap: a longer gap seeds the rest pose instead of stepping through it */
    private static final double MAX_DT = 0.25;
    private static final int MAX_STEPS = 8;
    /** world gravity, blocks/tick^2 (the same scale the ballistics use) */
    private static final double GRAVITY = 0.04;
    /** low-pass on the frame acceleration: finite differences of a mouse-driven
     *  pose are noisy and the chain would turn that noise into a buzz */
    private static final double ACCEL_SMOOTH = 0.7;
    /** frame-acceleration cap, blocks/tick^2: ~5 g, so a hitch cannot fling the chain */
    private static final double MAX_FRAME_ACC = 0.2;
    /** below this a direction is degenerate: keep the previous pose */
    private static final double MIN_DIR = 1.0E-5;

    // ---- geometry (read once per baked model) ----
    private static BakedGeoModel cachedModel;
    /** bones the physics drives, one per link: {@code chain_i}, or the single
     *  {@code pendant} bone when the model ships no chain */
    private static CoreGeoBone[] links = new CoreGeoBone[0];
    /** rest position per point, charm-local blocks */
    private static double[] restPos = new double[0];
    /** rest direction per link, charm-local unit vector (index 0 unused) */
    private static double[] restDir = new double[0];
    /** rest length per link (index 0 unused) */
    private static double[] restLen = new double[0];
    /** inverse mass per point: the root is pinned (0 = infinite mass) */
    private static double[] invMass = new double[0];

    // ---- simulation state (charm-local blocks) ----
    private static double[] pos = new double[0];
    private static double[] prev = new double[0];
    /** positions at the end of the previous step, for render interpolation */
    private static double[] lastStep = new double[0];
    /** what the bones are aimed at: {@link #lastStep} lerped to {@link #pos} */
    private static double[] render = new double[0];
    /** how far the receiver clamp pushed each point out along local +Y, blocks */
    private static double[] surfPush = new double[0];
    private static long lastGunId;
    private static long lastStepNs;
    private static double accumulator;
    private static boolean seeded;

    // ---- frame capture: charm-local <-> world ----
    private static final Quaterniond toLocal = new Quaterniond();
    private static final Vector3d pivotWorld = new Vector3d();
    private static final Vector3d downLocal = new Vector3d(0, -1, 0);
    // the charm origin's motion, source of the non-inertial pseudo-force
    private static final Vector3d pivotPrev = new Vector3d();
    private static final Vector3d pivotVel = new Vector3d();
    private static final Vector3d pivotAcc = new Vector3d();
    private static final Vector3d pivotAccPrev = new Vector3d();
    private static final Vector3d pivotAccLocal = new Vector3d();
    private static boolean havePivotVel;

    // ---- scratch (never allocate inside the step loop) ----
    private static final Quaternionf qf = new Quaternionf();
    private static final Quaternionf qf2 = new Quaternionf();
    private static final Vector3f v0 = new Vector3f();
    private static final Quaternionf absQ = new Quaternionf();
    private static final Quaternionf relQ = new Quaternionf();
    private static final Quaternionf prevQ = new Quaternionf();
    private static final Matrix3f mat3 = new Matrix3f();
    private static final Vector3f euler = new Vector3f();

    private CharmPhysics() {}

    /**
     * Simulate and write the chain bones. Call with the pose stack already at
     * the charm's model origin (after {@code applyBoneChain}) and BEFORE
     * {@code reRender}, and AFTER the module's animation pass — the animation
     * processor restores undriven bones to rest, so physics must have the last
     * word on the chain.
     *
     * @return true when the simulation drove the bones (false = rest pose)
     */
    public static boolean update(ItemStack stack, ModuleDefinition def, BakedGeoModel model,
                                 PoseStack poseStack, long gunId) {
        if (!Config.charmPhysics) return false;
        if (!GunHandsLayer.isFirstPersonPass || !GunModulesLayer.animationsEnabled) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.getMainHandItem() != stack) return false;
        if (!ensureGeometry(model, def)) return false;

        capture(poseStack, mc);

        long now = System.nanoTime();
        double dt = seeded ? (now - lastStepNs) / 1.0E9 : 0.0;
        lastStepNs = now;
        updateFrameAccel(dt);
        if (!seeded || gunId != lastGunId || dt <= 0 || dt > MAX_DT) {
            // first frame, a different gun, or a pause: seed at the captured
            // pose instead of stepping through the gap (the world moved on)
            seedRest();
            seeded = true;
            lastGunId = gunId;
            accumulator = 0;
        } else {
            ModuleDefinition.CharmSpec spec = def.charm;
            accumulator += dt;
            double drag = Math.exp(-spec.airDrag() * STEP_SECONDS);
            // gravity along world down, plus the frame's pseudo-force -m*a;
            // both are accelerations, so both scale by the step squared
            double g = GRAVITY * spec.gravityScale() * STEP_TICKS * STEP_TICKS;
            double a = STEP_TICKS * STEP_TICKS;
            double fx = downLocal.x * g - pivotAccLocal.x * a;
            double fy = downLocal.y * g - pivotAccLocal.y * a;
            double fz = downLocal.z * g - pivotAccLocal.z * a;
            for (int i = 0; i < MAX_STEPS && accumulator >= STEP_SECONDS; i++) {
                accumulator -= STEP_SECONDS;
                step(spec, drag, fx, fy, fz);
            }
            if (accumulator > STEP_SECONDS) accumulator = 0; // capped catch-up
        }
        // Render between steps. At 60-75 fps the accumulator advances one or
        // two steps per frame, so the raw step positions jitter against a gun
        // that moves every frame; lerping the last step to this one removes it.
        double alpha = accumulator / STEP_SECONDS;
        for (int i = 0; i < points * 3; i++) {
            render[i] = lastStep[i] + (pos[i] - lastStep[i]) * alpha;
        }
        writeBones();
        return true;
    }

    /**
     * Acceleration of the charm's origin, from finite differences of its world
     * position. Uniform motion (walking) cancels out; turning, the walk bob
     * and recoil survive, and those are exactly the moments the chain should
     * lag. Consumed by the step loop as a non-inertial frame's pseudo-force.
     */
    private static void updateFrameAccel(double dt) {
        double dtTicks = dt * 20.0;
        if (!havePivotVel || dtTicks <= 1.0E-6) {
            pivotVel.set(0, 0, 0);
            pivotAcc.set(0, 0, 0);
        } else {
            pivotAcc.set(pivotWorld).sub(pivotPrev).div(dtTicks).sub(pivotVel).div(dtTicks);
            pivotVel.set(pivotWorld).sub(pivotPrev).div(dtTicks);
            pivotAcc.lerp(pivotAccPrev, ACCEL_SMOOTH);
            double am = pivotAcc.length();
            if (am > MAX_FRAME_ACC) pivotAcc.mul(MAX_FRAME_ACC / am);
        }
        pivotAccPrev.set(pivotAcc);
        pivotPrev.set(pivotWorld);
        havePivotVel = true;
        pivotAccLocal.set(pivotAcc).rotate(toLocal);
    }

    // ---------------------------------------------------------------
    // geometry
    // ---------------------------------------------------------------

    /**
     * Reads the chain layout off the live baked model. A bone's pivot is its
     * rotation centre in ABSOLUTE model space: {@code RenderUtils.prepMatrixForBone}
     * translates by the pivot and back again with nothing in between, so the
     * pivot itself is the bone's model-space position — it is never accumulated
     * down the parent chain. Cached per baked model object, which also covers
     * resource reloads: GeckoLib re-bakes new instances, so the identity check
     * invalidates itself.
     */
    private static boolean ensureGeometry(BakedGeoModel model, ModuleDefinition def) {
        if (model == cachedModel) return true;
        CoreGeoBone[] probed = new CoreGeoBone[MAX_SEGMENTS];
        int segments = 0;
        while (segments < MAX_SEGMENTS) {
            CoreGeoBone bone = model.getBone("chain_" + segments).orElse(null);
            if (bone == null) break;
            probed[segments++] = bone;
        }
        CoreGeoBone mass = model.getBone(MASS_BONE).orElse(null);
        if (mass == null) mass = model.getBone(PENDANT_BONE).orElse(null);
        CoreGeoBone[] fresh;
        if (segments > 0) {
            fresh = new CoreGeoBone[segments];
            System.arraycopy(probed, 0, fresh, 0, segments);
        } else {
            // no chain bones: one joint. The pendant hangs from its own pivot
            // and swings as a rigid body, so the pendant bone IS the link.
            CoreGeoBone pendant = model.getBone(PENDANT_BONE).orElse(null);
            if (pendant == null || mass == null || mass == pendant) {
                LOGGER.warn("Charm {} ships no chain_0 and no usable pendant/loc_mass pair; "
                        + "physics stays off", def.id);
                return false;
            }
            fresh = new CoreGeoBone[] { pendant };
        }
        if (mass == null) {
            LOGGER.warn("Charm {} has no loc_mass or pendant bone to mark the pendant's "
                    + "center of mass; physics stays off", def.id);
            return false;
        }
        int count = fresh.length + 1;
        points = count;
        links = fresh;
        restPos = new double[count * 3];
        restDir = new double[count * 3];
        restLen = new double[count];
        invMass = new double[count];
        pos = new double[count * 3];
        prev = new double[count * 3];
        lastStep = new double[count * 3];
        render = new double[count * 3];
        surfPush = new double[count];
        savedRot = new float[fresh.length * 3];
        for (int i = 0; i < fresh.length; i++) localPivot(fresh[i], i);
        localPivot(mass, fresh.length);
        for (int i = 1; i < points; i++) {
            double dx = restPos[i * 3] - restPos[(i - 1) * 3];
            double dy = restPos[i * 3 + 1] - restPos[(i - 1) * 3 + 1];
            double dz = restPos[i * 3 + 2] - restPos[(i - 1) * 3 + 2];
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < MIN_DIR) return false; // degenerate chain
            restLen[i] = len;
            restDir[i * 3] = dx / len;
            restDir[i * 3 + 1] = dy / len;
            restDir[i * 3 + 2] = dz / len;
        }
        // the root is kinematic (infinite mass); the pendant carries the rest
        invMass[0] = 0;
        for (int i = 1; i < points - 1; i++) invMass[i] = 1;
        invMass[points - 1] = 1.0 / def.charm.pendantMass();
        cachedModel = model;
        seeded = false;
        return true;
    }

    /** model-space position of a bone's pivot: its own absolute pivot, blocks */
    private static void localPivot(CoreGeoBone bone, int index) {
        restPos[index * 3] = bone.getPivotX() / 16.0;
        restPos[index * 3 + 1] = bone.getPivotY() / 16.0;
        restPos[index * 3 + 2] = bone.getPivotZ() / 16.0;
    }

    // ---------------------------------------------------------------
    // capture
    // ---------------------------------------------------------------

    /**
     * Samples the charm's world transform from the live pose stack. First
     * person renders from the camera basis, so the pose stack holds view space
     * ({@code v = R·(world - camPos)}) — undone here with the exact convention
     * {@code MuzzleAnchor} documents and {@code MuzzleSmoke} consumes.
     */
    private static void capture(PoseStack poseStack, Minecraft mc) {
        Matrix4f m = poseStack.last().pose();
        Camera cam = mc.gameRenderer.getMainCamera();
        Quaternionf inv = new Quaternionf()
                .rotationY((float) Math.toRadians(-(cam.getYRot() + 180.0f)))
                .rotateX((float) Math.toRadians(-cam.getXRot()));
        // view-space translation is small (the gun sits ~0.5 blocks from the
        // eye), so float is ample before the double camera position is added
        v0.set(m.m30(), m.m31(), m.m32()).rotate(inv);
        Vec3 cp = cam.getPosition();
        pivotWorld.set(cp.x + v0.x, cp.y + v0.y, cp.z + v0.z);
        qf.set(inv).mul(qf2.setFromUnnormalized(m));
        toLocal.set(qf).normalize().conjugate();
        // the charm's own "down": world gravity expressed in the charm's frame
        downLocal.set(0, -1, 0).rotate(toLocal);
    }

    /** seed every point at its rest position in the charm's frame */
    private static void seedRest() {
        for (int i = 0; i < points; i++) {
            set(pos, i, restPos[i * 3], restPos[i * 3 + 1], restPos[i * 3 + 2]);
            set(prev, i, restPos[i * 3], restPos[i * 3 + 1], restPos[i * 3 + 2]);
        }
        System.arraycopy(pos, 0, lastStep, 0, pos.length);
        System.arraycopy(pos, 0, render, 0, pos.length);
        havePivotVel = false;
        pivotVel.set(0, 0, 0);
        pivotAcc.set(0, 0, 0);
        pivotAccPrev.set(0, 0, 0);
        pivotAccLocal.set(0, 0, 0);
    }

    // ---------------------------------------------------------------
    // simulation
    // ---------------------------------------------------------------

    /** one fixed step: inertia, the frame's forces, the pin, then a combined
     *  constraint pass over link distances, swing cone and receiver surface */
    private static void step(ModuleDefinition.CharmSpec spec, double drag,
                             double fx, double fy, double fz) {
        System.arraycopy(pos, 0, lastStep, 0, pos.length);
        for (int i = 0; i < points; i++) surfPush[i] = 0;
        for (int i = 3; i < points * 3; i++) {
            double v = (pos[i] - prev[i]) * drag;
            prev[i] = pos[i];
            pos[i] += v;
        }
        for (int i = 1; i < points; i++) {
            pos[i * 3] += fx;
            pos[i * 3 + 1] += fy;
            pos[i * 3 + 2] += fz;
        }
        // the root rides the mount; in this frame that is a fixed position, so
        // nothing teleports the chain around the way a world-space pin does
        set(pos, 0, restPos[0], restPos[1], restPos[2]);
        set(prev, 0, restPos[0], restPos[1], restPos[2]);
        double maxCos = Math.cos(Math.toRadians(spec.maxSwingDegrees()));
        boolean limit = spec.maxSwingDegrees() < 180;
        // No early exit: a Gauss-Seidel sweep is not exact even for bare links
        // (solving link i+1 moves link i's base again), so a chain that stops
        // early keeps a little stretch that the next step adds to — measured
        // at 14% after two seconds where the full run settles at 0.03%.
        for (int it = 0; it < ITERATIONS; it++) {
            solveDistances();
            if (limit) solveCone(maxCos);
            solveSurface(spec.surfaceMargin());
        }
        contact(spec);
    }

    /** link lengths; sweeping root-outward propagates the pin in one pass */
    private static void solveDistances() {
        for (int i = 1; i < points; i++) {
            double dx = pos[i * 3] - pos[(i - 1) * 3];
            double dy = pos[i * 3 + 1] - pos[(i - 1) * 3 + 1];
            double dz = pos[i * 3 + 2] - pos[(i - 1) * 3 + 2];
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d < MIN_DIR) continue;
            double w0 = invMass[i - 1], w1 = invMass[i];
            double ws = w0 + w1;
            if (ws <= 0) continue;
            double k = (d - restLen[i]) / d;
            pos[(i - 1) * 3] += dx * (w0 / ws) * k;
            pos[(i - 1) * 3 + 1] += dy * (w0 / ws) * k;
            pos[(i - 1) * 3 + 2] += dz * (w0 / ws) * k;
            pos[i * 3] -= dx * (w1 / ws) * k;
            pos[i * 3 + 1] -= dy * (w1 / ws) * k;
            pos[i * 3 + 2] -= dz * (w1 / ws) * k;
        }
    }

    /**
     * Swing cone: a link whose direction leaves the cone about world down is
     * rotated back onto the boundary AT ITS REST LENGTH, so the clamp cannot
     * stretch the chain and the distance pass has nothing to undo.
     */
    private static void solveCone(double maxCos) {
        double sin = Math.sqrt(Math.max(0, 1 - maxCos * maxCos));
        for (int i = 1; i < points; i++) {
            double dx = pos[i * 3] - pos[(i - 1) * 3];
            double dy = pos[i * 3 + 1] - pos[(i - 1) * 3 + 1];
            double dz = pos[i * 3 + 2] - pos[(i - 1) * 3 + 2];
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d < MIN_DIR) continue;
            double ux = dx / d, uy = dy / d, uz = dz / d;
            double cos = ux * downLocal.x + uy * downLocal.y + uz * downLocal.z;
            if (cos >= maxCos) continue;
            // the nearest cone edge lies in the plane spanned by downLocal and
            // the component of the link direction perpendicular to it (a cross
            // product would land it 90 degrees around the vertical instead)
            double px = ux - downLocal.x * cos, py = uy - downLocal.y * cos;
            double pz = uz - downLocal.z * cos;
            double ul = Math.sqrt(px * px + py * py + pz * pz);
            if (ul < MIN_DIR) continue;
            double nx = downLocal.x * maxCos + px / ul * sin;
            double ny = downLocal.y * maxCos + py / ul * sin;
            double nz = downLocal.z * maxCos + pz / ul * sin;
            set(pos, i, pos[(i - 1) * 3] + nx * d, pos[(i - 1) * 3 + 1] + ny * d,
                    pos[(i - 1) * 3 + 2] + nz * d);
        }
    }

    /** receiver surface: local y >= margin. The root already sits on the mount. */
    private static void solveSurface(double margin) {
        for (int i = 1; i < points; i++) {
            if (pos[i * 3 + 1] < margin) {
                surfPush[i] += margin - pos[i * 3 + 1];
                pos[i * 3 + 1] = margin;
            }
        }
    }

    /**
     * Contact response, once per step. The distance pass has already put every
     * point where it belongs, so the only thing a collision may change is
     * velocity: the correction accumulated over the solve is the contact
     * normal, and a point still moving into it keeps its tangential share
     * ({@code friction}) and rebounds by {@code bounce}.
     */
    private static void contact(ModuleDefinition.CharmSpec spec) {
        double b = spec.bounce(), f = spec.friction();
        for (int i = 1; i < points; i++) {
            // Only the receiver is a contact; the swing cone is a limit the
            // view imposes. Charging the cone friction bleeds the chain's
            // tangential speed on every step it rides the boundary — which is
            // most of a fast turn — and that reads as swinging through treacle.
            if (surfPush[i] <= MIN_DIR) continue;
            double vx = pos[i * 3] - prev[i * 3];
            double vy = pos[i * 3 + 1] - prev[i * 3 + 1];
            double vz = pos[i * 3 + 2] - prev[i * 3 + 2];
            if (vy >= 0) continue; // separating along the surface normal (local +Y)
            set(prev, i, pos[i * 3] - vx * f, pos[i * 3 + 1] + vy * b,
                    pos[i * 3 + 2] - vz * f);
        }
    }

    // ---------------------------------------------------------------
    // bones
    // ---------------------------------------------------------------

    /**
     * Aims every link bone at the next simulated point. A link's absolute
     * rotation is the minimal arc from its rest direction to its live
     * direction; the bone carries the parent-relative part, so the chain
     * composes down the hierarchy exactly like an authored animation would.
     * The pendant bone is left alone: it rides the last link.
     */
    private static void writeBones() {
        for (int i = 0; i < links.length; i++) {
            CoreGeoBone bone = links[i];
            savedRot[i * 3] = bone.getRotX();
            savedRot[i * 3 + 1] = bone.getRotY();
            savedRot[i * 3 + 2] = bone.getRotZ();
        }
        wrote = true;
        prevQ.identity();
        for (int i = 0; i < links.length; i++) {
            double dx = render[(i + 1) * 3] - render[i * 3];
            double dy = render[(i + 1) * 3 + 1] - render[i * 3 + 1];
            double dz = render[(i + 1) * 3 + 2] - render[i * 3 + 2];
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d < MIN_DIR) continue;
            arc(absQ, (float) restDir[(i + 1) * 3], (float) restDir[(i + 1) * 3 + 1],
                    (float) restDir[(i + 1) * 3 + 2],
                    (float) (dx / d), (float) (dy / d), (float) (dz / d));
            relQ.set(prevQ).conjugate().mul(absQ);
            prevQ.set(absQ);
            relQ.get(mat3);
            mat3.getEulerAnglesZYX(euler);
            CoreGeoBone bone = links[i];
            bone.setRotX(euler.x);
            bone.setRotY(euler.y);
            bone.setRotZ(euler.z);
        }
    }

    /** chain bone rotations captured before the last physics write */
    private static float[] savedRot = new float[0];
    private static boolean wrote;

    /**
     * Put the chain bones back. The bones live on the shared BakedGeoModel, so
     * a physics pose left in place would leak into the third-person, inventory
     * and dropped-item passes (the same hazard the rest-pose branch guards
     * against). Call right after the module's draw.
     */
    public static void restore() {
        if (!wrote) return;
        for (int i = 0; i < links.length; i++) {
            CoreGeoBone bone = links[i];
            bone.setRotX(savedRot[i * 3]);
            bone.setRotY(savedRot[i * 3 + 1]);
            bone.setRotZ(savedRot[i * 3 + 2]);
        }
        wrote = false;
    }

    /** minimal arc rotation taking unit vector a to unit vector b */
    private static void arc(Quaternionf dest, float ax, float ay, float az,
                            float bx, float by, float bz) {
        float dot = ax * bx + ay * by + az * bz;
        if (dot > 0.999999f) {
            dest.identity();
            return;
        }
        if (dot < -0.999999f) {
            // antiparallel: 180 degrees about any perpendicular axis
            float px = 0, py = 1, pz = 0;
            if (Math.abs(ay) > 0.9f) {
                px = 1;
                py = 0;
            }
            float cx = ay * pz - az * py, cy = az * px - ax * pz, cz = ax * py - ay * px;
            float cl = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
            if (cl < 1.0E-6f) {
                dest.identity();
                return;
            }
            dest.setAngleAxis((float) Math.PI, cx / cl, cy / cl, cz / cl);
            return;
        }
        dest.set(ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx, 1.0f + dot).normalize();
    }

    private static void set(double[] arr, int point, double x, double y, double z) {
        arr[point * 3] = x;
        arr[point * 3 + 1] = y;
        arr[point * 3 + 2] = z;
    }
}
