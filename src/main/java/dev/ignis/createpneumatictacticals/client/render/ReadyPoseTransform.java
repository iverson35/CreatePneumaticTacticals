package dev.ignis.createpneumatictacticals.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

/**
 * First-person low/high ready transform (plan_v2): while sprinting or elytra
 * flying the gun swings into the configured ready pose; returns to the firing
 * stance over readyDelayMs (ReadyModel drives the progress).
 */
public final class ReadyPoseTransform {

    private ReadyPoseTransform() {}

    /**
     * Applies the ready pose scaled by eased progress.
     * Call first-person only, before the geckolib render.
     *
     * @param highMix 0 = low ready, 1 = high ready; low and high are
     *                cross-faded per DOF so switching poses mid-run blends
     *                smoothly instead of snapping
     */
    // runtime-tunable via /cptpose (defaults = the original hand-tuned pose);
    // X/Y/Z are block offsets, PITCH/YAW/ROLL are degrees
    // (pitch negative = muzzle up; yaw positive = muzzle swings left;
    //  roll positive = gun tips counterclockwise seen from behind)
    public static float HIGH_X = 0f,    HIGH_Y = 0.3f,  HIGH_Z = -0.7f;
    public static float HIGH_PITCH = 50f, HIGH_YAW = 0f, HIGH_ROLL = 5f;
    public static float LOW_X = -0.7f,  LOW_Y = -0.09f, LOW_Z = 0f;
    public static float LOW_PITCH = -15f, LOW_YAW = 45f, LOW_ROLL = 0f;

    public static void apply(PoseStack poseStack, float highMix, float progress) {
        if (progress <= 0.001f) return;
        float p = progress * progress * (3f - 2f * progress); // smoothstep, same as ADS
        // per-DOF lerp between the low and high ready poses
        float m = highMix * highMix * (3f - 2f * highMix);   // smoothstep the cross-fade too
        float roll  = LOW_ROLL  + (HIGH_ROLL  - LOW_ROLL)  * m;
        float yaw   = LOW_YAW   + (HIGH_YAW   - LOW_YAW)   * m;
        float pitch = LOW_PITCH + (HIGH_PITCH - LOW_PITCH) * m;
        float x = LOW_X + (HIGH_X - LOW_X) * m;
        float y = LOW_Y + (HIGH_Y - LOW_Y) * m;
        float z = LOW_Z + (HIGH_Z - LOW_Z) * m;
        // rotations in ZYX order (roll applied first in model space, then
        // yaw, then pitch) so each axis can be tuned independently
        poseStack.mulPose(Axis.ZP.rotationDegrees(roll * p));
        poseStack.mulPose(Axis.YP.rotationDegrees(yaw * p));
        poseStack.mulPose(Axis.XP.rotationDegrees(pitch * p));
        poseStack.translate(x * p, y * p, z * p);
    }
}
