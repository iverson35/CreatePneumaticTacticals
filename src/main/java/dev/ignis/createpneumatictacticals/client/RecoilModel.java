package dev.ignis.createpneumatictacticals.client;

/**
 * Three-layer recoil (plan_v2 后坐力系统):
 * 1. view: pitch up per shot, decays toward zero
 * 2. model: gun model kick via damped spring (consumed by renderer)
 * 3. screen shake: slight shake scaled by recoilMultiplier
 *
 * <p>Integration is wall-clock based and runs lazily inside the getters, so
 * the decay/spring advance once per rendered frame instead of once per tick —
 * 20&nbsp;Hz tick integration made the recovery visibly steppy. The ODE
 * constants keep the same tuning as the old per-tick values (dt 0.05s):
 * decay 0.15/tick ≈ e^(-3.25/s), shake 0.8/tick ≈ e^(-4.46/s).
 */
public final class RecoilModel {

    private static final double VIEW_DECAY_RATE = 3.25; // /s, scaled by recoilRecovery
    private static final double SHAKE_DECAY_RATE = 4.46; // /s
    private static final double SPRING_STIFFNESS = 300;
    private static final double SPRING_DAMPING = 20;
    private static final double MAX_DT = 0.1; // pause/hitch guard

    private static double pitchOffset = 0;   // degrees, positive = up
    private static double yawOffset = 0;     // degrees
    private static double springPos = 0;     // model kick, arbitrary units
    private static double springVel = 0;
    private static double shake = 0;
    private static double recoveryScale = 1; // from gun recoilRecovery stat
    private static long lastUpdateNs = 0;

    private RecoilModel() {}

    public static void onShot(double basePitch, double baseYaw, double recoilMult, boolean aiming,
                              double recoilRecovery) {
        update();
        recoveryScale = Math.max(0, 1 + recoilRecovery);
        double scale = recoilMult * (aiming ? 0.7 : 1.0);
        // hipfire camera shake halved — full hipfire view kick was nauseating
        double viewScale = aiming ? scale : scale * 0.5;
        pitchOffset += basePitch * viewScale;
        yawOffset += (Math.random() * 2 - 1) * baseYaw * viewScale;
        springVel += 40 * scale;
        shake += 1.5 * scale;
    }

    /** advance all layers to now; idempotent within the same nanos */
    private static void update() {
        long now = System.nanoTime();
        if (lastUpdateNs == 0) { lastUpdateNs = now; return; }
        double dt = (now - lastUpdateNs) / 1e9;
        lastUpdateNs = now;
        if (dt <= 0) return;
        if (dt > MAX_DT) dt = MAX_DT;

        double viewDecay = Math.exp(-VIEW_DECAY_RATE * recoveryScale * dt);
        pitchOffset *= viewDecay;
        yawOffset *= viewDecay;

        // damped spring, semi-implicit euler
        springVel += (-springPos * SPRING_STIFFNESS - springVel * SPRING_DAMPING) * dt;
        springPos += springVel * dt;

        shake *= Math.exp(-SHAKE_DECAY_RATE * dt);
    }

    /** camera pitch offset in degrees */
    public static double pitchDegrees() {
        update();
        return pitchOffset;
    }

    /** camera yaw offset in degrees */
    public static double yawDegrees() {
        update();
        return yawOffset;
    }

    /** gun model kick amount (renderer) */
    public static double modelKick() {
        update();
        return springPos;
    }

    public static double shake() {
        update();
        return shake;
    }

    public static void clear() {
        pitchOffset = yawOffset = springPos = springVel = shake = 0;
        lastUpdateNs = 0;
    }
}
