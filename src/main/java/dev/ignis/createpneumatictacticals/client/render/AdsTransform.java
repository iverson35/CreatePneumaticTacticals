package dev.ignis.createpneumatictacticals.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ignis.createpneumatictacticals.client.AimHandler;
import dev.ignis.createpneumatictacticals.gun.GunNbt;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import software.bernie.geckolib.cache.GeckoLibCache;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;

/**
 * First-person ADS transform: slides/rotates the whole gun so the receiver
 * model's camera locator bone lands on the view axis (screen center).
 *
 * <h2>Model authoring convention (Blockbench)</h2>
 * Add up to two EMPTY locator bones (no cubes) at the ROOT of the receiver
 * geo model:
 * <ul>
 *   <li>{@code ads_camera} — pivot = the eye point behind the main sight
 *       (the sight rings this point when aiming),</li>
 *   <li>{@code tactical_camera} — same for the canted/tactical sight;
 *       give the bone the cant rotation (e.g. Z 45°) and the renderer tilts
 *       the whole gun by its inverse so the side sight comes up level.</li>
 * </ul>
 * An installed sight module may OVERRIDE the receiver bone by defining the
 * same-named bone in its own model (its pivot is composed with the
 * receiver's {@code loc_sight} / {@code loc_sight_side} pivot). Missing
 * everywhere = that stance keeps the hip position. pointblank does the
 * same thing with its {@code scope} bones (theirs reads a cube midpoint;
 * ours reads the pivot so no dummy cube is needed).
 *
 * <h2>Math</h2>
 * Self-calibrating, no hardcoded pose constants: the current pose matrix
 * maps item space to view space. The gun is rolled around the item
 * origin (the hand anchor) by the inverse of the bone's authored cant,
 * then translated so the rolled eye point lands on the view axis.
 * Works under any vanilla first-person transform, bobbing and swing.
 * Model-&gt;render space conversion: GeoBone pivots are mirrored at load
 * (pivotX = -json.x), so in block units a bone sits at
 * {@code (pivotX/16, pivotY/16, pivotZ/16)} plus GeoItemRenderer's
 * preRender translate {@code (0.5, 0.51, 0.5)}.
 */
public final class AdsTransform {

    /** how far in front of the camera the eye point rests (blocks) */
    private static final float SIGHT_DISTANCE = 0.06f;

    private AdsTransform() {}

    /**
     * Applies the ADS transform scaled by the eased aim progress.
     * Call before the geckolib render, first-person contexts only.
     */
    public static void apply(ItemStack gun, PoseStack poseStack) {
        float frameTime = net.minecraft.client.Minecraft.getInstance().getFrameTime();
        float p = AimHandler.aimProgress(frameTime);
        if (p <= 0.001f) return;
        p = p * p * (3f - 2f * p);
        float b = AimHandler.stanceBlend(frameTime);
        b = b * b * (3f - 2f * b);

        BakedGeoModel baked = GeckoLibCache.getBakedModels().get(GunAssets.forStack(gun).model());
        if (baked == null) return;

        // both stance corrections against the SAME unmodified pose matrix
        Matrix4f poseInv = new Matrix4f(poseStack.last().pose()).invert();
        Vector4f t = poseInv.transform(new Vector4f(0f, 0f, -SIGHT_DISTANCE, 1f));
        Vector3f targetItem = new Vector3f(t.x, t.y, t.z);

        StanceCorr main = compute(gun, baked, false, targetItem);
        StanceCorr tac = compute(gun, baked, true, targetItem);
        if (main == null && tac == null) return;

        // blend the two stances: the eye bone position lerps, the cant
        // rotation slerps (a stance without a camera bone keeps the other
        // side's value; a missing rotation blends toward "no cant"), then
        // everything ramps with aim progress — nothing happens while at hip
        Quaternionf stanceInv = main != null && main.invRot != null
                ? new Quaternionf(main.invRot) : new Quaternionf();
        if (tac != null && tac.invRot != null) {
            stanceInv.slerp(tac.invRot, b);
        }
        Quaternionf rot = new Quaternionf().slerp(stanceInv, p);

        Vector3f pivotA = main != null ? main.bonePos : tac.bonePos;
        Vector3f pivotB = tac != null ? tac.bonePos : main.bonePos;
        Vector3f eye = new Vector3f(pivotA).lerp(pivotB, b);

        // roll the gun around the hand anchor (the item origin, where the
        // item is held), NOT around the eye point: the gun cants in place
        // around the grip and the sight swings up to the eye, instead of the
        // whole gun sweeping around your eyeball
        Vector3f anchor = new Vector3f(0.5f, 0.51f, 0.5f);
        rot.transform(eye.sub(anchor)).add(anchor);

        // then slide the rolled eye point onto the view axis
        Vector3f corr = new Vector3f(targetItem).sub(eye).mul(p);
        poseStack.translate(corr.x, corr.y, corr.z);
        poseStack.translate(anchor.x, anchor.y, anchor.z);
        poseStack.mulPose(rot);
        poseStack.translate(-anchor.x, -anchor.y, -anchor.z);
    }


    /** one stance's data: eye bone position + inverse of its authored rotation */
    private record StanceCorr(Vector3f bonePos, Quaternionf invRot) {}

    /**
     * Computes one stance's correction; null when no camera bone exists for it
     * (that stance then keeps the hip position). An installed sight module
     * OVERRIDES the receiver bone (plan_v2: the sight decides the eye point);
     * its pivot composes with the receiver's mount locator pivot.
     */
    private static StanceCorr compute(ItemStack gun, BakedGeoModel baked, boolean tactical, Vector3f targetItem) {
        String boneName = tactical ? "tactical_camera" : "ads_camera";
        String mountName = tactical ? "loc_sight_side" : "loc_sight";

        GeoBone bone = null;
        Vector3f mountPivotPx = new Vector3f();
        var sight = GunNbt.readModules(gun).get(tactical
                ? dev.ignis.createpneumatictacticals.module.ModuleType.TACTICAL_SIGHT
                : dev.ignis.createpneumatictacticals.module.ModuleType.SIGHT);
        if (sight != null) {
            BakedGeoModel sightModel = GeckoLibCache.getBakedModels().get(
                    new net.minecraft.resources.ResourceLocation(sight.id.getNamespace(),
                            "geo/gun/" + sight.id.getPath() + ".geo.json"));
            if (sightModel != null) bone = sightModel.getBone(boneName).orElse(null);
            if (bone != null) {
                GeoBone mount = baked.getBone(mountName).orElse(null);
                if (mount != null) mountPivotPx.set(mount.getPivotX(), mount.getPivotY(), mount.getPivotZ());
            }
        }
        if (bone == null) bone = baked.getBone(boneName).orElse(null);
        if (bone == null) return null;

        // bone eye point in geckolib item-render space (blocks). GeoBone
        // pivots are ALREADY mirrored at load (pivotX = -json.x) and
        // RenderUtils.translateToPivotPoint uses them raw — do NOT negate x again
        Vector3f bonePos = new Vector3f(
                (bone.getPivotX() + mountPivotPx.x) / 16f,
                (bone.getPivotY() + mountPivotPx.y) / 16f,
                (bone.getPivotZ() + mountPivotPx.z) / 16f);
        bonePos.add(0.5f, 0.51f, 0.5f); // GeoItemRenderer.preRender translate

        // GeckoLib folds the bedrock mirror in at load time (rotX/rotY
        // negated, rotZ kept) and applies the bone rotation in render space
        // as Z·Y·X (RenderUtils.rotateMatrixAroundBone). The snapshot values
        // therefore ARE the visual rotation — replicate that product exactly
        // and invert it to level the canted sight out.
        var snap = bone.getInitialSnapshot();
        float rx = snap.getRotX(), ry = snap.getRotY(), rz = snap.getRotZ();
        Quaternionf invRot = (rx != 0 || ry != 0 || rz != 0)
                ? new Quaternionf().rotateZ(rz).rotateY(ry).rotateX(rx).invert() : null;
        return new StanceCorr(bonePos, invRot);
    }
}
