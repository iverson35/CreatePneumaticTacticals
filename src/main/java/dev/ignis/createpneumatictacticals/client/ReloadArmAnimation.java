package dev.ignis.createpneumatictacticals.client;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Third-person reload left-arm choreography, driven by the pose broadcast:
 * when RELOADING appears the left arm swings down and holds low with a
 * slight sway; when the pose leaves, it swings back up. Empty-magazine
 * reloads (RELOADING_EMPTY) finish with a short inward tap after the
 * up-swing — the bolt-rack beat.
 *
 * <p>Timeline is edge-driven: observers don't know the reload duration, so
 * the hold phase runs until the pose flips and the out phase runs after it.
 * Per-entity state, render thread only.
 */
public final class ReloadArmAnimation {

    /** swing-down duration at reload start */
    private static final long DOWN_MS = 280;
    /** swing-up duration at reload end */
    private static final long UP_MS = 260;
    /** empty reload: inward tap duration after the up-swing */
    private static final long TAP_MS = 220;
    /** left-arm swing-down offset (xRot + lowers the arm from the foregrip) */
    private static final float DOWN_XROT = 0.575f;
    /** hold-phase sway amplitude */
    private static final float SWAY_AMP = 0.05f;
    /** empty reload tap: left arm yRot + swings it inward (toward the body) */
    private static final float TAP_YROT = 0.45f;

    private static final class Reload {
        long startMs;
        long outStartMs = -1;
        boolean empty;
    }

    private static final Map<Integer, Reload> STATES = new ConcurrentHashMap<>();

    private ReloadArmAnimation() {}

    public static void apply(int entityId, boolean reloading, boolean empty,
                             float ageInTicks, ModelPart leftArm) {
        long now = System.currentTimeMillis();
        Reload r = STATES.get(entityId);
        if (reloading) {
            if (r == null) {
                r = new Reload();
                r.startMs = now;
                STATES.put(entityId, r);
            } else if (r.outStartMs >= 0) {
                // reload restarted mid out-phase: replay from the top
                r.startMs = now;
                r.outStartMs = -1;
            }
            r.empty = empty;
            long t = now - r.startMs;
            if (t < DOWN_MS) {
                float p = smooth(t / (float) DOWN_MS);
                leftArm.xRot += DOWN_XROT * p + sway(ageInTicks) * p;
            } else {
                leftArm.xRot += DOWN_XROT + sway(ageInTicks);
            }
            return;
        }
        if (r == null) return;
        // out phase: swing up, then the empty-reload inward tap
        if (r.outStartMs < 0) r.outStartMs = now;
        long t = now - r.outStartMs;
        if (t < UP_MS) {
            float p = smooth(t / (float) UP_MS);
            leftArm.xRot += (DOWN_XROT + sway(ageInTicks)) * (1f - p);
        } else if (r.empty && t < UP_MS + TAP_MS) {
            float p = (t - UP_MS) / (float) TAP_MS;
            leftArm.yRot += TAP_YROT * (float) Math.sin(p * Math.PI);
        } else {
            STATES.remove(entityId);
        }
    }

    private static float sway(float ageInTicks) {
        return Mth.sin(ageInTicks * 0.35f) * SWAY_AMP;
    }

    private static float smooth(float t) {
        return t * t * (3f - 2f * t);
    }
}
