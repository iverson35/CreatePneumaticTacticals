package dev.ignis.createpneumatictacticals.client;

import dev.ignis.createpneumatictacticals.Config;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

/**
 * Low/high ready pose state machine (plan_v2): sprinting or elytra flying
 * with a gun holsters it into the ready pose; after stopping, the gun needs
 * {@code readyDelayMs} to return to the firing stance — firing is locked
 * until fully recovered. Aiming suppresses the pose (aim wins).
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
        stowed = holdingGun && !AimHandler.isAiming()
                && (player.isSprinting() || player.isFallFlying());
        if (stowed) {
            progress = Math.min(1f, progress + TICK_MS / HOLSTER_MS);
        } else {
            // ergonomics shortens the sprint->fire recovery delay: recovery
            // takes readyDelayMs / ergo, clamped to 1/3..4x the base delay
            double ergo = holdingGun ? AimHandler.ergoScaleOf(player.getMainHandItem()) : 1.0;
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
