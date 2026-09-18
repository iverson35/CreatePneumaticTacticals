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
 * maps item space to view space, so the correction is
 * {@code itemInv * targetView - boneItem}. Works under any vanilla
 * first-person transform, bobbing and swing.
 * Model-&gt;render space conversion (GeoBone#getModelPosition convention):
 * {@code (x,y,z)px -> (-x/16, y/16, z/16) blocks}, plus GeoItemRenderer's
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
        float p = AimHandler.aimProgress(net.minecraft.client.Minecraft.getInstance().getFrameTime());
        if (p <= 0.001f) return;
        p = p * p * (3f - 2f * p);

        boolean tactical = "tactical".equals(GunNbt.getAimStance(gun));
        String boneName = tactical ? "tactical_camera" : "ads_camera";
        String mountName = tactical ? "loc_sight_side" : "loc_sight";

        BakedGeoModel baked = GeckoLibCache.getBakedModels().get(GunAssets.forStack(gun).model());
        if (baked == null) return;

        // installed sight overrides the receiver's camera bone (plan_v2: the
        // sight decides the eye point); its pivot composes with the receiver's
        // mount locator
        GeoBone bone = null;
        Vector3f mountPivotPx = new Vector3f();
        var mods = GunNbt.readModules(gun);
        var sight = mods.get(tactical
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
        if (bone == null) return;

        // bone eye point in geckolib item-render space (blocks); a sight
        // override composes its pivot with the receiver's mount locator pivot
        Vector3f bonePos = new Vector3f(
                -(bone.getPivotX() + mountPivotPx.x) / 16f,
                (bone.getPivotY() + mountPivotPx.y) / 16f,
                (bone.getPivotZ() + mountPivotPx.z) / 16f);
        bonePos.add(0.5f, 0.51f, 0.5f); // GeoItemRenderer.preRender translate

        Matrix4f pose = new Matrix4f(poseStack.last().pose());
        Matrix4f poseInv = new Matrix4f(pose).invert();

        // target: view-space point just in front of the camera
        Vector4f targetItem = poseInv.transform(new Vector4f(0f, 0f, -SIGHT_DISTANCE, 1f));
        Vector3f corr = new Vector3f(targetItem.x, targetItem.y, targetItem.z).sub(bonePos).mul(p);

        // bone point after the correction; rotation pivots around it
        Vector3f q = new Vector3f(bonePos).add(corr);
        poseStack.translate(corr.x, corr.y, corr.z);

        // undo the bone's authored rotation (render-space X mirror negates
        // Y/Z rotations; invert the whole thing) so a canted sight levels out
        var snap = bone.getInitialSnapshot();
        float rx = snap.getRotX(), ry = -snap.getRotY(), rz = -snap.getRotZ();
        if (rx != 0 || ry != 0 || rz != 0) {
            Quaternionf inv = new Quaternionf()
                    .rotateXYZ(rx, ry, rz)
                    .invert();
            Quaternionf rot = new Quaternionf().slerp(inv, p);
            poseStack.translate(q.x, q.y, q.z);
            poseStack.mulPose(rot);
            poseStack.translate(-q.x, -q.y, -q.z);
        }
    }
}
