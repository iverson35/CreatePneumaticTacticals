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
    private static volatile UUID sampleOwner;

    private MuzzleAnchor() {}

    /**
     * Record the muzzle position during a render pass. Call with the pose
     * stack state AT the muzzle (after applyBoneChain) and the muzzle tip
     * offset in model space (block units, GeoCube bake already /16).
     */
    public static void capture(PoseStack poseStack, Vector3f modelTip, UUID playerId) {
        Matrix4f mat = poseStack.last().pose();
        Vector4f p = new Vector4f(modelTip, 1.0f).mul(mat);
        sample = new float[]{p.x, p.y, p.z};
        sampleOwner = playerId;
    }

    /** latest view-space sample (v = R·(world − camPos)); null while none exists */
    public static float[] viewSample() {
        UUID owner = sampleOwner;
        if (owner == null) return null;
        float[] v = sample;
        if (v != null && Float.isFinite(v[0]) && Float.isFinite(v[1]) && Float.isFinite(v[2])) return v;
        return null;
    }

    public static void reset() {
        sample = new float[]{Float.NaN, Float.NaN, Float.NaN};
        sampleOwner = null;
    }
}