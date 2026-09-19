package dev.ignis.createpneumatictacticals.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.UUID;

/**
 * Captures the muzzle tip's position during the gun's render pass so muzzle
 * smoke spawns at the actual gun tip in first AND third person. The render
 * pass is the only place the animated bone transform is known; firing code
 * runs outside the render loop.
 *
 * <p>Both passes produce the SAME space: view space, v = R·(world − camPos)
 * where R is the camera rotation. First person renders from an identity base
 * (GameRenderer.renderItemInHand setIdentity) which IS the camera basis;
 * third person renders entities on the stack renderLevel already rotated by
 * mulPose(XP(pitch)·YP(yaw+180)) — the same R. Consumers convert with
 * world = camPos + R⁻¹·v, R⁻¹ = YP(−(yaw+180))·XP(−pitch).
 */
public final class MuzzleAnchor {

    private static volatile float[] sample = {Float.NaN, Float.NaN, Float.NaN};
    private static volatile float[] muzzleUp = {Float.NaN, Float.NaN, Float.NaN};
    private static volatile UUID sampleOwner;

    private MuzzleAnchor() {}

    /**
     * Record the muzzle position (and the muzzle's forward axis) during a
     * render pass. Call with the pose stack state AT the muzzle (after
     * applyBoneChain) and the muzzle tip offset in model space (block units,
     * GeoCube bake already /16).
     *
     * <p>The captured axis is the model -Z direction transformed by the same
     * pose matrix — in view space. It carries EVERYTHING the renderer did
     * to the gun (stance cant, recoil kick, pose animations), so consumers
     * rotating gas-guide directions with it are automatically consistent
     * with what is on screen; no separate quaternion bookkeeping.
     */
    public static void capture(PoseStack poseStack, Vector3f modelTip, UUID playerId) {
        Matrix4f mat = poseStack.last().pose();
        Vector4f p = new Vector4f(modelTip, 1.0f).mul(mat);
        sample = new float[]{p.x, p.y, p.z};
        // model up = +Y through the same pose matrix, rotation-only
        // (w=0 makes the translation column irrelevant). The UP vector is
        // what carries the gun's ROLL: a stance cant rotates the gun about
        // the barrel axis, which leaves the muzzle's forward direction
        // unchanged but tips the up vector over — gas-guide port phases key
        // off it.
        Vector4f up = new Vector4f(0, 1, 0, 0).mul(mat);
        float upLen = (float) Math.sqrt(up.x * up.x + up.y * up.y + up.z * up.z);
        if (upLen > 1.0E-4f) {
            muzzleUp = new float[]{up.x / upLen, up.y / upLen, up.z / upLen};
        }
        sampleOwner = playerId;
    }

    /** latest view-space muzzle tip (v = R·(world − camPos)); null while none exists */
    public static float[] viewSample() {
        UUID owner = sampleOwner;
        if (owner == null) return null;
        float[] v = sample;
        if (v != null && Float.isFinite(v[0]) && Float.isFinite(v[1]) && Float.isFinite(v[2])) return v;
        return null;
    }


    /** latest view-space muzzle UP axis (model +Y through the live pose
     * matrix — carries the gun's roll); null while no capture exists */
    public static float[] viewUp() {
        UUID owner = sampleOwner;
        if (owner == null) return null;
        float[] u = muzzleUp;
        if (u != null && Float.isFinite(u[0]) && Float.isFinite(u[1]) && Float.isFinite(u[2])) return u;
        return null;
    }

    public static void reset() {
        sample = new float[]{Float.NaN, Float.NaN, Float.NaN};
        muzzleUp = new float[]{Float.NaN, Float.NaN, Float.NaN};
        sampleOwner = null;
    }
}