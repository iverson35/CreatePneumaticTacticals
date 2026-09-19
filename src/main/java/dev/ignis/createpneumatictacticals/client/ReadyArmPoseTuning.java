package dev.ignis.createpneumatictacticals.client;

/**
 * Third-person low/high ready arm offsets — 手调这个文件.
 *
 * Applied on top of the CROSSBOW_HOLD base pose in HumanoidModelMixin,
 * scaled by a smooth blend factor (0..1), so values are pure offsets.
 *
 * Per arm, per pose:
 *   xRot/yRot/zRot — rotation added to the shoulder (radians; 1 rad ≈ 57°)
 *                    arm forward-hold ≈ xRot -1.4; more negative = raised
 *   x/y/z          — pivot translation added to the arm root (model units)
 *
 * Defaults preserve the original single-constant feel; zero out what you
 * don't need.
 */
public final class ReadyArmPoseTuning {

    // ================= blend state machine (render thread) =================
    private static final float RAMP_MS = 150f;

    private static final class Blend {
        float value;    // 0 = firing stance, 1 = ready stance
        long lastMs;
        boolean high;   // last ready style, kept while blending out
    }

    private static final java.util.Map<Integer, Blend> ADS_BLENDS = new java.util.concurrent.ConcurrentHashMap<>();

    /** ADS arm ramp at ergonomics 1; matches AimHandler.AIM_TIME_SECONDS */
    private static final float ADS_RAMP_MS = 180f;

    /**
     * Same as {@link #update} but for the aiming arm pose (ADS + tactical
     * share one offset set). The ramp is scaled by the entity's gun
     * ergonomics so third-person arms stay in sync with the first-person
     * ADS transition; non-gun holders fall back to the base 180ms.
     */
    public static float updateAds(int entityId, boolean ads, net.minecraft.world.item.ItemStack gun) {
        Blend b = ADS_BLENDS.computeIfAbsent(entityId, k -> new Blend());
        long now = System.currentTimeMillis();
        long dt = Math.min(100L, now - b.lastMs);
        b.lastMs = now;
        float rampMs = (float) (ADS_RAMP_MS
                / dev.ignis.createpneumatictacticals.gun.GunStats.ergoScale(gun));
        b.value = net.minecraft.util.Mth.clamp(b.value + (ads ? dt : -dt) / rampMs, 0f, 1f);
        if (!ads && b.value == 0f) ADS_BLENDS.remove(entityId, b);
        return b.value * b.value * (3 - 2 * b.value);
    }
    private static final java.util.Map<Integer, Blend> BLENDS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Advances the per-entity ready-pose blend and returns the eased factor
     * (smoothstep 0..1) plus the style to apply.
     */
    public static BlendResult update(int entityId, boolean ready, boolean high) {
        Blend b = BLENDS.computeIfAbsent(entityId, k -> new Blend());
        if (ready) b.high = high;
        long now = System.currentTimeMillis();
        long dt = Math.min(100L, now - b.lastMs);
        b.lastMs = now;
        b.value = net.minecraft.util.Mth.clamp(b.value + (ready ? dt : -dt) / RAMP_MS, 0f, 1f);
        if (!ready && b.value == 0f) BLENDS.remove(entityId, b);
        float e = b.value * b.value * (3 - 2 * b.value);
        return new BlendResult(e, b.high);
    }

    public record BlendResult(float ease, boolean high) {}
    public static final class ArmOffset {
        public float xRot, yRot, zRot;
        public float x, y, z;
    }

    // ================= LOW READY (枪口朝下) =================
    public static final ArmOffset LOW_RIGHT = new ArmOffset();
    public static final ArmOffset LOW_LEFT = new ArmOffset();

    // ================= HIGH READY (枪口朝上) =================
    public static final ArmOffset HIGH_RIGHT = new ArmOffset();
    public static final ArmOffset HIGH_LEFT = new ArmOffset();

    // ================= ADS (瞄准, 主瞄/侧瞄共用) =================
    public static final ArmOffset ADS_RIGHT = new ArmOffset();
    public static final ArmOffset ADS_LEFT = new ArmOffset();

    static {
        LOW_RIGHT.xRot = 0.6f;   // arm drops, muzzle dips
        LOW_RIGHT.yRot = -0.5f;
        LOW_LEFT.xRot = 0.7f;
        LOW_LEFT.yRot = -0.3f;
        HIGH_RIGHT.xRot = -0.6f; // arm raised, muzzle up
        HIGH_RIGHT.yRot = 0.3f;
        HIGH_LEFT.xRot = -0.4f;
        HIGH_LEFT.yRot = 0.3f;
        ADS_RIGHT.xRot = -0.1f; // forward hold onto the gun
        ADS_RIGHT.yRot = 0.2f;
        ADS_LEFT.xRot = -0.1f;
        ADS_LEFT.yRot = 0.1f;
    }

    private ReadyArmPoseTuning() {}
}
