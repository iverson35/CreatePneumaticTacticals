package dev.ignis.createpneumatictacticals.client;

/**
 * Three-layer recoil (plan_v2 后坐力系统):
 * 1. view: pitch up + a random left/right yaw kick per shot, decays to zero
 *    (VIEW_KICK_SCALE: the camera kick is 2x the gun's own kick). The kick is
 *    eased in and out: the offset approaches its amplitude at VIEW_RISE_RATE
 *    (63% in 40 ms) instead of stepping there, so the lift reads as a short
 *    fast-in/slow-out rise rather than a teleport; the aim (and therefore the
 *    click-time punch dir) is untouched by this
 * 2. model: gun model kick via damped spring (consumed by renderer) —
 *    vertical-only by design: backward push + muzzle flip, no lateral kick
 * 3. screen shake: slight shake scaled by the vertical multiplier
 *
 * <p>The two recoil axes scale independently: a module's vertical multiplier
 * drives the camera pitch, the model kick and the shake; its horizontal
 * multiplier drives only the camera yaw.
 *
 * <p>A new shot before the previous rebound finished BAKES the leftover
 * offset into the player's real rotation (the current camera pose becomes
 * the new baseline), then injects its own kick — only the last shot's
 * rebound ever runs, and residuals genuinely accumulate: full-auto climbs
 * the aim point shot over shot, so recoil control is a thing.
 *
 * <p>Integration is wall-clock based and runs lazily inside the getters, so
 * the decay/spring advance once per rendered frame instead of once per tick —
 * 20&nbsp;Hz tick integration made the recovery visibly steppy. The ODE
 * constants keep the same tuning as the old per-tick values (dt 0.05s):
 * old per-tick 0.15 mapped to e^(-3.25/s), halved to e^(-1.625/s)
 * (rebound speed halved); shake 0.8/tick ≈ e^(-4.46/s).
 */
public final class RecoilModel {

    private static final double VIEW_DECAY_RATE = 1.625; // /s, scaled by recoilRecovery (rebound halved)
    /** /s, exponential approach of the kick itself: 63% in 20 ms, 95% in 60 ms */
    private static final double VIEW_RISE_RATE = 50.0;
    private static final double SHAKE_DECAY_RATE = 4.46; // /s
    private static final double SPRING_STIFFNESS = 300;
    private static final double SPRING_DAMPING = 20;
    private static final double MAX_DT = 0.1; // pause/hitch guard
    /** camera-only multiplier: aim-point kick vs the raw base_recoil_* values */
    private static final double VIEW_KICK_SCALE = 1.5;

    private static double pitchOffset = 0;   // degrees, positive = up
    private static double yawOffset = 0;     // degrees
    private static double pitchTarget = 0;   // amplitude the lift is rising to
    private static double yawTarget = 0;
    private static double rise = 1;          // 0..1 progress of the current lift
    private static double springPos = 0;     // model kick, arbitrary units
    private static double springVel = 0;
    private static double shake = 0;
    private static double recoveryScale = 1; // from gun recoilRecovery stat
    private static long lastUpdateNs = 0;

    private RecoilModel() {}

    public static void onShot(double basePitch, double baseYaw, double verticalMult, double horizontalMult,
                              boolean aiming, double recoilRecovery,
                              net.minecraft.world.entity.player.Player player) {
        update();
        // new shot before the previous rebound finished: bake the leftover
        // offset into the REAL player rotation — the current camera pose
        // becomes the new baseline, and only this last kick ever rebounds.
        // Residuals thus accumulate shot over shot: full-auto genuinely
        // climbs the aim point and recoil control becomes a thing.
        if (player != null && (pitchOffset != 0 || yawOffset != 0)) {
            player.setXRot(player.getXRot() - (float) pitchOffset);
            player.setYRot(player.getYRot() - (float) yawOffset);
        }
        // carry whatever of the last lift had not finished: the bake above only
        // caught the visible part, so nothing of a kick is lost when the next
        // shot interrupts the rise
        double carryPitch = pitchTarget - pitchOffset;
        double carryYaw = yawTarget - yawOffset;
        pitchOffset = 0;
        yawOffset = 0;
        rise = 0;
        recoveryScale = Math.max(0, 1 + recoilRecovery);
        double aimMult = aiming ? 0.7 : 1.0;
        double vScale = verticalMult * aimMult;
        double hScale = horizontalMult * aimMult;
        // hipfire camera shake halved — full hipfire view kick was nauseating
        double vView = aiming ? vScale : vScale * 0.5;
        double hView = aiming ? hScale : hScale * 0.5;
        // amplitudes only: update() eases the visible offset up to them, so the
        // lift is a short fast-in/slow-out rise instead of an instant step. The
        // aim itself is untouched - the curl above already baked it - and the
        // crosshair keeps leading the aim by the visible offset, which is
        // exactly what the click-time punch dir reports to the server.
        pitchTarget = carryPitch + basePitch * vView * VIEW_KICK_SCALE;
        // random-signed: held fire genuinely walks the aim left and right
        yawTarget = carryYaw + (Math.random() * 2 - 1) * baseYaw * hView * VIEW_KICK_SCALE;
        // model kick + screen shake follow the receiver's base recoil, so
        // tuning a gun's base_recoil_pitch scales the whole feel, not just
        // the view angle (constants normalized to the former fixed kick at
        // base 1.2: 33*1.2 = 40, 1.25*1.2 = 1.5). Vertical-only: the model
        // never kicks sideways, so horizontal-recoil mods must not quiet it.
        springVel += 33.0 * basePitch * vScale;
        shake += 1.25 * basePitch * vScale;
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
        pitchTarget *= viewDecay;
        yawTarget *= viewDecay;
        rise = 1 - (1 - rise) * Math.exp(-VIEW_RISE_RATE * dt);
        pitchOffset = pitchTarget * rise;
        yawOffset = yawTarget * rise;

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
        pitchOffset = yawOffset = pitchTarget = yawTarget = springPos = springVel = shake = 0;
        rise = 1;
        lastUpdateNs = 0;
    }
}
