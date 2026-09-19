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

    /** pose-in duration (ms); pose-out takes readyDelayMs by spec */
    private static final float HOLSTER_MS = 150f;
    private static final float TICK_MS = 50f;

    private static float progress = 0f;     // 0 = firing stance, 1 = ready pose
    private static float prevProgress = 0f;
    private static boolean stowed = false;

    private ReadyModel() {}

    public static void tick(Player player, boolean holdingGun) {
        prevProgress = progress;
        double ergo = holdingGun
                ? dev.ignis.createpneumatictacticals.gun.GunStats.ergoScale(player.getMainHandItem())
                : 1.0;
        boolean sprintFire = ergo > dev.ignis.createpneumatictacticals.gun.GunStats.SPRINT_FIRE_ERGO;
        boolean sprintStow = player.isSprinting();
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
            progress = Math.min(1f, progress + TICK_MS / HOLSTER_MS);
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
}
