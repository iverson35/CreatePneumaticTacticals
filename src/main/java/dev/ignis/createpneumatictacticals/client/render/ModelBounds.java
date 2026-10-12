package dev.ignis.createpneumatictacticals.client.render;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.cache.object.GeoVertex;

import java.util.Set;

/**
 * The bounding box of the geometry a GeckoLib model actually DRAWS, in blocks,
 * relative to the model root: {@code [minX, minY, minZ, maxX, maxY, maxZ]}, or
 * {@code null} for a model that draws nothing.
 *
 * <p>The walk reproduces the renderer's own transform chain — bone pos, pivot,
 * rotation and scale ({@code RenderUtils.prepMatrixForBone}), then the cube's
 * pivot and rotation ({@code GeoRenderer.renderCube}) — applied to the baked
 * quad vertices. Those vertices are already bone-local blocks with the cube's
 * inflate baked in, and the baked cube pivot is NOT part of them: adding it (as
 * the first version of this helper did) shifted every cube that carries a
 * pivot, so parts measured taller and lower than they are (a receiver came out
 * 0.87 blocks tall instead of 0.47, its bottom 0.18 blocks too low) and models
 * with rotated bones or cubes came out skewed.
 *
 * <p>Shared by every "measure a model" caller: the workbench's staged gun, the
 * tuning table's laid-out module, the machine tool's clamp and its monitor.
 */
public final class ModelBounds {

    /** FX bones that must not count: the laser beam box reaches the beam's 16+ block range */
    private static final Set<String> SKIPPED_BONES = Set.of(ModuleDraw.BEAM_BONE);

    /** mutable union of vertex positions, in blocks */
    static final class Acc {
        private float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY,
                minZ = Float.POSITIVE_INFINITY;
        private float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY,
                maxZ = Float.NEGATIVE_INFINITY;
        private boolean valid;

        void add(float x, float y, float z) {
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
            valid = true;
        }

        @Nullable
        float[] array() {
            return valid ? new float[]{minX, minY, minZ, maxX, maxY, maxZ} : null;
        }
    }

    /** the model's own bounds; null when it draws nothing */
    @Nullable
    public static float[] of(BakedGeoModel model) {
        Acc acc = new Acc();
        accumulate(model, acc, 0f, 0f, 0f);
        return acc.array();
    }

    /**
     * Union {@code model}'s drawn geometry into {@code acc}, its root
     * translated by {@code (ox, oy, oz)} blocks — how the workbench walks a
     * gun's receiver and its installed modules into a single box.
     */
    public static void accumulate(BakedGeoModel model, Acc acc, float ox, float oy, float oz) {
        Matrix4f root = new Matrix4f().translation(ox, oy, oz);
        Vector3f scratch = new Vector3f();
        for (GeoBone bone : model.topLevelBones()) {
            accumulateBone(bone, root, acc, scratch);
        }
    }

    private static void accumulateBone(GeoBone bone, Matrix4f parent, Acc acc, Vector3f scratch) {
        if (SKIPPED_BONES.contains(bone.getName())) return;
        Matrix4f m = new Matrix4f(parent);
        // RenderUtils.prepMatrixForBone: pos (model x mirrored), pivot, rotation, scale
        float px = bone.getPivotX() / 16f, py = bone.getPivotY() / 16f, pz = bone.getPivotZ() / 16f;
        m.translate(-bone.getPosX() / 16f, bone.getPosY() / 16f, bone.getPosZ() / 16f);
        m.translate(px, py, pz);
        m.rotate(new Quaternionf().rotationXYZ(bone.getRotX(), bone.getRotY(), bone.getRotZ()));
        m.scale(bone.getScaleX(), bone.getScaleY(), bone.getScaleZ());
        m.translate(-px, -py, -pz);
        for (GeoCube cube : bone.getCubes()) {
            accumulateCube(cube, m, acc, scratch);
        }
        for (GeoBone child : bone.getChildBones()) {
            accumulateBone(child, m, acc, scratch);
        }
    }

    private static void accumulateCube(GeoCube cube, Matrix4f boneMatrix, Acc acc, Vector3f scratch) {
        Matrix4f m = new Matrix4f(boneMatrix);
        // GeoRenderer.renderCube: rotate the cube around its own pivot (blocks)
        Vec3 pivot = cube.pivot();
        float px = (float) pivot.x / 16f, py = (float) pivot.y / 16f, pz = (float) pivot.z / 16f;
        Vec3 rotation = cube.rotation();
        m.translate(px, py, pz);
        m.rotate(new Quaternionf().rotationXYZ((float) rotation.x, (float) rotation.y,
                (float) rotation.z));
        m.translate(-px, -py, -pz);
        for (GeoQuad quad : cube.quads()) {
            if (quad == null) continue;
            for (GeoVertex vertex : quad.vertices()) {
                Vector3f p = m.transformPosition(scratch.set(vertex.position()));
                acc.add(p.x, p.y, p.z);
            }
        }
    }

    private ModelBounds() {
    }
}
