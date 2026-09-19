package dev.ignis.createpneumatictacticals.client;

import dev.ignis.createpneumatictacticals.ammo.AmmoExtension;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Client-side hipfire spread model (plan_v2 腰射精度模型):
 *   final = (S0 * posePenalty + bloom) / hipfireAccuracy
 *   bloom: +15%*S0 per shot, cap 2.5*S0, linear decay over 0.4s after last shot
 *   pose-penalty transitions interpolated over 250ms
 *
 * <p>Fully lazy: every read interpolates from wall-clock timestamps at
 * render time, so the crosshair gap animates per frame. A per-tick
 * integration made the gap visibly step at 20&nbsp;Hz.
 */
public final class SpreadModel {

    private static final long BLOOM_DECAY_MS = 400;
    private static final long INTERP_MS = 250;

    private static double bloom = 0;          // accumulated bloom in degrees
    private static long lastShotMs = 0;
    private static double penaltyFrom = 1.0;  // penalty at transition start
    private static double penaltyTarget = 1.0;
    private static long lastPenaltyChangeMs = 0;

    private SpreadModel() {}

    /** pose penalty interpolated to now; retargets when the pose changed */
    private static double currentPenalty(Player player, long now) {
        double target = posePenalty(player);
        if (target != penaltyTarget) {
            penaltyFrom = penaltyAt(now);
            penaltyTarget = target;
            lastPenaltyChangeMs = now;
        }
        return penaltyAt(now);
    }

    private static double penaltyAt(long now) {
        double t = Math.min(1.0, (now - lastPenaltyChangeMs) / (double) INTERP_MS);
        return penaltyFrom + (penaltyTarget - penaltyFrom) * t;
    }

    public static double currentSpread(Player player, ItemStack gun, AmmoExtension ext, double hipfireAcc) {
        long now = now();
        double s0 = ext.spread;
        long sinceShot = now - lastShotMs;
        double bloomNow = bloom * Math.max(0, 1 - sinceShot / (double) BLOOM_DECAY_MS);
        double raw = s0 * currentPenalty(player, now) + bloomNow;
        double spread = Math.max(0, raw / Math.max(0.1, hipfireAcc));
        // aiming tightens to pinpoint as the ADS transition completes
        float p = AimHandler.aimProgress(Minecraft.getInstance().getFrameTime());
        p = p * p * (3f - 2f * p);
        return spread * (1.0 - p);
    }

    private static double posePenalty(Player player) {
        // base hipfire pose penalties doubled (standing 2, walking/air 6,
        // crouch 1.6, sprint 16) — hip was too accurate for all poses.
        // Keep in sync with GunFireHandler.posePenalty (server-authoritative).
        // sprint-fire (high-ergonomics guns only) is wildly inaccurate
        if (player.isSprinting() || player.isFallFlying()) return 16.0;
        if (!player.onGround()) return 6.0;
        if (player.isCrouching()) return 1.6;
        // walking: velocity OR the per-tick walkDist increment (walkDist is
        // the animation driver, guaranteed populated client-side; deltaMovement
        // alone proved unreliable in some setups)
        if (player.getDeltaMovement().horizontalDistanceSqr() > 0.02
                || player.walkDist - player.walkDistO > 0.02f) return 6.0;
        return 2.0;
    }

    public static void addBloom(AmmoExtension ext) {
        bloom = Math.min(2.5 * ext.spread, bloom + 0.15 * ext.spread);
        lastShotMs = now();
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
