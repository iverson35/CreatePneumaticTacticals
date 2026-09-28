package dev.ignis.createpneumatictacticals.client;

import java.util.Random;

/**
 * Three-layer recoil (plan_v2 后坐力系统):
 * 1. view: pitch up + a left/right yaw kick per shot, decays to zero
 *    (VIEW_KICK_SCALE: the camera kick is 2x the gun's own kick). The kick is
 *    eased in and out: the offset approaches its amplitude at VIEW_RISE_RATE
 *    (63% in 40 ms) instead of stepping there, so the lift reads as a short
 *    fast-in/slow-out rise rather than a teleport; the aim (and therefore the
 *    click-time punch dir) is untouched by this
 * 2. model: gun model kick via damped spring (consumed by renderer) —
 *    vertical-only by design: backward push + muzzle flip, no lateral kick
 * 3. screen shake: slight shake scaled by the vertical multiplier
 *
 * <p>The kick itself is a {@link RecoilPattern} — the receiver id seeds a
 * fixed per-shot curve (see that class), so a spray is learnable instead of
 * random, with a small seeded jitter layered on top so two strings are close
 * but not identical. The shot index counts within one string: it restarts
 * when the fire pauses for longer than {@link #BURST_GAP_NS} (or the gun
 * changes), which is what makes the pattern reproducible per magazine.
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

    // --- spray pattern state (see RecoilPattern) ---

    /** fire gap that starts a new string: 4+ missed shots at 600 rpm */
    private static final long BURST_GAP_NS = 400_000_000L;
    /** jitter as a fraction of the receiver's base values (±5% / ±8%) */
    private static final double JITTER_PITCH = 0.05;
    private static final double JITTER_YAW = 0.08;

    private static RecoilPattern pattern;
    private static net.minecraft.resources.ResourceLocation patternId;
    private static int patternSlot = -1;
    private static int shotIndex = 0;    // 1-based index within the string
    private static long lastShotNs = 0;
    /** seeded per gun so the jitter is deterministic too — and NOT reseeded
     *  per string, so the stream keeps advancing and each string's jitter
     *  differs slightly from the last (that is the "close but not identical") */
    private static final Random JITTER = new Random();

    private RecoilModel() {}

    public static void onShot(double basePitch, double baseYaw, double verticalMult, double horizontalMult,
                              boolean aiming, double recoilRecovery,
                              net.minecraft.resources.ResourceLocation receiverId, int slot,
                              net.minecraft.world.entity.player.Player player) {
        update();
        // spray pattern: the receiver id picks the curve, the string (gun +
        // a short fire gap) picks the shot index within it. The jitter stream
        // is reseeded only when the GUN changes — a fresh string keeps
        // drawing from it, which is what makes two strings of the same gun
        // close but not identical.
        long now = System.nanoTime();
        boolean newGun = pattern == null || !receiverId.equals(patternId) || slot != patternSlot;
        if (newGun || now - lastShotNs > BURST_GAP_NS) {
            if (newGun) {
                pattern = RecoilPattern.of(receiverId);
                patternId = receiverId;
                patternSlot = slot;
                JITTER.setSeed(pattern.seed());
            }
            shotIndex = 0;
        }
        lastShotNs = now;
        shotIndex++;
        double patternPitch = pattern.vertical(shotIndex);
        double patternYaw = pattern.horizontal(shotIndex);
        double jitterPitch = (JITTER.nextDouble() * 2 - 1) * JITTER_PITCH;
        double jitterYaw = (JITTER.nextDouble() * 2 - 1) * JITTER_YAW;
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
        double vScale = verticalMult * aimMult; // model kick + shake (unscaled)
        double vView = viewKickScale(verticalMult, aiming);
        double hView = viewKickScale(horizontalMult, aiming);
        // amplitudes only: update() eases the visible offset up to them, so the
        // lift is a short fast-in/slow-out rise instead of an instant step. The
        // aim itself is untouched - the curl above already baked it - and the
        // crosshair keeps leading the aim by the visible offset, which is
        // exactly what the click-time punch dir reports to the server.
        pitchTarget = carryPitch + basePitch * (patternPitch + jitterPitch) * vView * VIEW_KICK_SCALE;
        // the pattern walks the aim left and right along a fixed curve instead
        // of a fresh coin flip per shot (jitter keeps it from being exact)
        yawTarget = carryYaw + baseYaw * (patternYaw + jitterYaw) * hView * VIEW_KICK_SCALE;
        // model kick + screen shake follow the receiver's base recoil, so
        // tuning a gun's base_recoil_pitch scales the whole feel, not just
        // the view angle (constants normalized to the former fixed kick at
        // base 1.2: 33*1.2 = 40, 1.25*1.2 = 1.5). Vertical-only: the model
        // never kicks sideways, so horizontal-recoil mods must not quiet it.
        // The unjittered pattern value: the spring already smooths it, and
        // jitter there would only make the model twitch.
        springVel += 33.0 * basePitch * patternPitch * vScale;
        shake += 1.25 * basePitch * patternPitch * vScale;
    }

    /**
     * Camera-kick factor for one recoil axis: the module multiplier, the aim
     * stance (ADS ×0.7) and the camera-only scale, hipfire halved — full
     * hipfire view kick was nauseating. Shared with {@link RecoilPreview} so
     * the workbench chart can never drift from what firing actually does.
     */
    public static double viewKickScale(double multiplier, boolean aiming) {
        double scale = multiplier * (aiming ? 0.7 : 1.0);
        return (aiming ? scale : scale * 0.5) * VIEW_KICK_SCALE;
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
        // the next shot starts a fresh string (pattern + jitter reseed)
        pattern = null;
        patternId = null;
        patternSlot = -1;
        shotIndex = 0;
        lastShotNs = 0;
    }
}
