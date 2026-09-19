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
     */
    // runtime-tunable via /cptpose (defaults = the original hand-tuned pose);
    // X/Y/Z are block offsets, PITCH/YAW/ROLL are degrees
    // (pitch negative = muzzle up; yaw positive = muzzle swings left;
    //  roll positive = gun tips counterclockwise seen from behind)
    public static float HIGH_X = 0f,    HIGH_Y = 0.3f,  HIGH_Z = -0.7f;
    public static float HIGH_PITCH = 50f, HIGH_YAW = 0f, HIGH_ROLL = 5f;
    public static float LOW_X = -0.7f,  LOW_Y = -0.09f, LOW_Z = 0f;
    public static float LOW_PITCH = -15f, LOW_YAW = 45f, LOW_ROLL = 0f;

    public static void apply(PoseStack poseStack, boolean high, float progress) {
        if (progress <= 0.001f) return;
        float p = progress * progress * (3f - 2f * progress); // smoothstep, same as ADS
        // rotations in ZYX order (roll applied first in model space, then
        // yaw, then pitch) so each axis can be tuned independently
        if (high) {
            poseStack.mulPose(Axis.ZP.rotationDegrees(HIGH_ROLL * p));
            poseStack.mulPose(Axis.YP.rotationDegrees(HIGH_YAW * p));
            poseStack.mulPose(Axis.XP.rotationDegrees(HIGH_PITCH * p));
            poseStack.translate(HIGH_X * p, HIGH_Y * p, HIGH_Z * p);
        } else {
            poseStack.mulPose(Axis.ZP.rotationDegrees(LOW_ROLL * p));
            poseStack.mulPose(Axis.YP.rotationDegrees(LOW_YAW * p));
            poseStack.mulPose(Axis.XP.rotationDegrees(LOW_PITCH * p));
            poseStack.translate(LOW_X * p, LOW_Y * p, LOW_Z * p);
        }
    }
}
