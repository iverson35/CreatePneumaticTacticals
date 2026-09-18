package dev.ignis.createpneumatictacticals.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.cache.object.GeoVertex;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Vanilla block-style 3/4 view for GUI slots, auto-fitted to the model.
 * GeoItemRenderer ignores the geo.json "display" section, and rotating a
 * model whose origin is far from its geometry flings it out of the slot —
 * so the baked model's bounding box is measured once (weak-keyed by the
 * baked model, so resource reloads re-measure), recentered, rotated with
 * vanilla's [30, 225, 0] block transform and scaled to fill the slot.
 */
public final class ItemGuiTransform {

    /** fraction of the slot the projected model should fill */
    private static final float FIT = 0.85f;

    /** center xyz + half-extents xyz, in pose units (1 block); keys die with the baked model on reload */
    private static final Map<BakedGeoModel, float[]> BOUNDS = new WeakHashMap<>();

    private ItemGuiTransform() {}

    public static void apply(PoseStack poseStack, BakedGeoModel model) {
        if (model == null) return;
        float[] b;
        synchronized (BOUNDS) {
            b = BOUNDS.computeIfAbsent(model, ItemGuiTransform::measure);
        }
        if (b == null) return; // empty model: nothing to fit

        Quaternionf rot = Axis.YP.rotationDegrees(75).mul(Axis.XP.rotationDegrees(20));
        // projected half-extent of the rotated bounding box on screen axes
        float spanX = 0, spanY = 0;
        for (int i = 0; i < 8; i++) {
            Vector3f w = new Vector3f(
                    (i & 1) == 0 ? b[3] : -b[3],
                    (i & 2) == 0 ? b[4] : -b[4],
                    (i & 4) == 0 ? b[5] : -b[5]).rotate(rot);
            spanX = Math.max(spanX, Math.abs(w.x));
            spanY = Math.max(spanY, Math.abs(w.y));
        }
        float scale = FIT / Math.max(2 * spanX, 2 * spanY);
        // Full vertex path: d_out + S·R·(v + t + d_in), where
        //   d_out = vanilla ItemRenderer's translate(-0.5, -0.5, -0.5) — OUTSIDE ours
        //   d_in  = GeoItemRenderer.preRender's translate(+0.5, +0.51, +0.5) — INSIDE ours
        // Solve t so the bbox center lands exactly on the pose origin:
        //   S·R·(c + t + d_in) = (0.5, 0.5, 0.5)  =>  t = R⁻¹·(0.5/s) - c - d_in
        Vector3f need = new Vector3f(0.5f / scale, 0.5f / scale, 0.5f / scale)
                .rotate(new Quaternionf(rot).invert());
        poseStack.scale(scale, scale, scale);
        poseStack.mulPose(rot);
        poseStack.translate(need.x - b[0] - 0.5f, need.y - b[1] - 0.51f, need.z - b[2] - 0.5f);
    }

    private static float[] measure(BakedGeoModel model) {
        float[] mm = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (GeoBone bone : model.topLevelBones()) {
            walk(bone, 0, 0, 0, mm);
        }
        if (mm[0] > mm[3]) return null;
        // geckolib already bakes vertices in block units (BB/16) — measure as-is
        return new float[]{
                (mm[0] + mm[3]) / 2f, (mm[1] + mm[4]) / 2f, (mm[2] + mm[5]) / 2f,
                (mm[3] - mm[0]) / 2f, (mm[4] - mm[1]) / 2f, (mm[5] - mm[2]) / 2f};
    }

    /** bone positions accumulate down the hierarchy; cube vertices are bone-local */
    private static void walk(GeoBone bone, float px, float py, float pz, float[] mm) {
        float x = px + bone.getPosX(), y = py + bone.getPosY(), z = pz + bone.getPosZ();
        for (GeoCube cube : bone.getCubes()) {
            for (GeoQuad quad : cube.quads()) {
                for (GeoVertex v : quad.vertices()) {
                    Vector3f p = v.position();
                    mm[0] = Math.min(mm[0], x + p.x); mm[3] = Math.max(mm[3], x + p.x);
                    mm[1] = Math.min(mm[1], y + p.y); mm[4] = Math.max(mm[4], y + p.y);
                    mm[2] = Math.min(mm[2], z + p.z); mm[5] = Math.max(mm[5], z + p.z);
                }
            }
        }
        for (GeoBone child : bone.getChildBones()) {
            walk(child, x, y, z, mm);
        }
    }
}
