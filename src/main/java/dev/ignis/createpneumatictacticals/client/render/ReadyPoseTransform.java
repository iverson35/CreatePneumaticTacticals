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
    public static void apply(PoseStack poseStack, boolean high, float progress) {
        if (progress <= 0.001f) return;
        float p = progress * progress * (3f - 2f * progress); // smoothstep, same as ADS
        if (high) {
            // muzzle up against the shoulder
            poseStack.translate(0, 0.05 * p, 0.08 * p);
            poseStack.mulPose(Axis.XP.rotationDegrees(-28f * p));
        } else {
            // muzzle down
            poseStack.translate(0, -0.09 * p, 0.05 * p);
            poseStack.mulPose(Axis.XP.rotationDegrees(33f * p));
        }
    }
}
