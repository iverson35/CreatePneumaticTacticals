package dev.ignis.createpneumatictacticals.client;

import dev.ignis.createpneumatictacticals.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

/**
 * Low/high ready pose state machine (plan_v2): sprinting or elytra flying
 * with a gun holsters it into the ready pose; after stopping, the gun needs
 * {@code readyDelayMs} (scaled by ergonomics) to return to the firing
 * stance — firing is locked until fully recovered. Aiming suppresses the
 * pose (aim wins), and so does a blocked muzzle.
 *
 * <p>Sprint-fire guns (ergonomics &gt; SPRINT_FIRE_ERGO) rest in the ready
 * pose while running as well, but holding attack — or a shot within the
 * last second — raises the gun over the recovery time (that recovery is the
 * sprint-fire delay); one second without firing drops it back to ready.
 *
 * <p>Tick-driven with partial-tick lerp, mirroring AimHandler.
 */
public final class ReadyModel {

    private static final float HOLSTER_MS = 150f;
    private static final float TICK_MS = 50f;

    /** cross-fade duration between low and high ready (ms) */
    private static final float POSE_MIX_MS = 150f;

    /** view pitch thresholds: below -15 degrees look up -> high ready,
     * above +15 degrees look down -> low ready; the +-15 degree band in
     * between keeps the current pose (hysteresis against jitter) */
    private static final float PITCH_TRIGGER = 15f;

    private static float progress = 0f;     // 0 = firing stance, 1 = ready pose
    private static float prevProgress = 0f;
    private static boolean stowed = false;
    private static boolean highReady = false;
    /** 0 = low, 1 = high; eased blend between the two ready poses */
    private static float highMix = 0f;
    private static float prevHighMix = 0f;

    private ReadyModel() {}
    public static void tick(Player player, boolean holdingGun) {
        prevProgress = progress;
        prevHighMix = highMix;
        prevProgress = progress;
        double ergo = holdingGun
                ? dev.ignis.createpneumatictacticals.gun.GunStats.ergoScale(player.getMainHandItem())
                : 1.0;
        boolean sprintFire = ergo > dev.ignis.createpneumatictacticals.gun.GunStats.SPRINT_FIRE_ERGO;
        // Releasing aim while still holding the sprint key: vanilla drops the
        // sprint flag for a couple of ticks, so isSprinting() alone would let
        // the gun fall ALL the way to hipfire before the ready pose kicks
        // back in — a visible down-then-up jerk, worst in high ready. Treat
        // the sprint key held + moving forward as sprint intent while the ADS
        // fade-out still runs, so the ready overlay takes over seamlessly.
        boolean sprintIntent = Minecraft.getInstance().options.keySprint.isDown()
                && Minecraft.getInstance().options.keyUp.isDown();
        boolean sprintStow = player.isSprinting() || sprintIntent;
        if (sprintStow && sprintFire) {
            // sprint-fire guns sit in the ready pose while running too, but
            // engaging raises the gun: holding attack (or a shot within the
            // last second) unstows it, and the readyDelayMs/ergo recovery
            // below IS the sprint-fire delay
            boolean engaging = Minecraft.getInstance().options.keyAttack.isDown()
                    || System.currentTimeMillis() - ClientGunInput.lastShotMs() < 1000;
            sprintStow = !engaging;
        }
        // elytra always stows; a blocked muzzle forces the ready pose
        // regardless of ergonomics
        stowed = holdingGun && !AimHandler.isAiming()
                && (player.isFallFlying()
                        || MuzzleClearance.isBlocked()
                        || sprintStow);
        if (stowed) {
            // pose follows the view pitch: looking up raises the muzzle
            // (high ready), looking down dips it (low ready); inside the
            // hysteresis band the current pose is kept
            if (player.getXRot() < -PITCH_TRIGGER) highReady = true;
            else if (player.getXRot() > PITCH_TRIGGER) highReady = false;
            highMix = Mth.clamp(highMix + (highReady ? 1 : -1) * TICK_MS / POSE_MIX_MS, 0f, 1f);
            // releasing ADS straight into the ready pose: hold progress at 1
            // while the ADS fade-out still runs. The decaying ADS alignment
            // then reveals the full ready pose instead of the two overlays
            // both sitting at half strength mid-transition, which dipped the
            // gun through hipfire (down-then-up, very visible in high ready)
            if (AimHandler.aimProgress(1f) > 0f) {
                progress = 1f;
            } else {
                progress = Math.min(1f, progress + TICK_MS / HOLSTER_MS);
            }
        } else {
            // ergonomics shortens the sprint->fire recovery delay: recovery
            // takes readyDelayMs / ergo, clamped to 1/3..4x the base delay
            float recoveryMs = (float) (Config.readyDelayMs / ergo);
            progress = Math.max(0f, progress - TICK_MS / Math.max(1f, recoveryMs));
        }
    }

    /** render interpolation */
    public static float progress(float partialTick) {
        return Mth.lerp(partialTick, prevProgress, progress);
    }

    /** firing is allowed only once the gun is fully back in the firing stance */
    public static boolean canFire() {
        return progress <= 0f;
    }

    /** currently in the ready pose (sprinting / flying with gun out) */
    public static boolean isStowed() {
        return stowed;
    }

    /** which ready pose: true = high (muzzle up), driven by view pitch */
    public static boolean isHighReady() {
        return highReady;
    }

    /** render interpolation of the low<->high cross-fade
     * (0 = fully low ready, 1 = fully high ready) */
    public static float highMix(float partialTick) {
        return Mth.lerp(partialTick, prevHighMix, highMix);
    }
}
