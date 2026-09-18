package dev.ignis.createpneumatictacticals.client;

import dev.ignis.createpneumatictacticals.ammo.AmmoExtension;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Client-side hipfire spread model (plan_v2 腰射精度模型):
 *   final = (S0 * posePenalty + bloom) / hipfireAccuracy
 *   bloom: +15%*S0 per shot, cap 2.5*S0, linear decay over 0.4s after last shot
 *   state transitions interpolated over 250ms
 */
public final class SpreadModel {

    private static final long BLOOM_DECAY_MS = 400;
    private static final long INTERP_MS = 250;

    private static double bloom = 0;          // accumulated bloom in degrees
    private static long lastShotMs = 0;
    private static double currentPenalty = 1.0; // interpolated penalty
    private static double targetPenalty = 1.0;
    private static long lastPenaltyChangeMs = 0;

    private SpreadModel() {}

    public static void onShot() {
        lastShotMs = now();
    }

    /** call each client tick to update interpolation/decay */
    public static void tick(Player player, ItemStack gun) {
        targetPenalty = posePenalty(player);
        long now = now();
        if (targetPenalty != currentPenalty) {
            long elapsed = now - lastPenaltyChangeMs;
            double t = Math.min(1.0, elapsed / (double) INTERP_MS);
            currentPenalty = currentPenalty + (targetPenalty - currentPenalty) * t;
            if (t >= 1.0) lastPenaltyChangeMs = now;
            else if (currentPenalty == targetPenalty) lastPenaltyChangeMs = now;
        }
        // decay bloom
        long sinceShot = now - lastShotMs;
        if (sinceShot > 0) {
            double decayFrac = Math.min(1.0, sinceShot / (double) BLOOM_DECAY_MS);
            // handled lazily in currentSpread: bloom stored as last value; decay computed on read
        }
    }

    public static double currentSpread(Player player, ItemStack gun, AmmoExtension ext, double hipfireAcc) {
        double s0 = ext.spread;
        long sinceShot = now() - lastShotMs;
        double bloomNow = bloom * Math.max(0, 1 - sinceShot / (double) BLOOM_DECAY_MS);
        if (Minecraft.getInstance().options.keyShift.isDown()) {
            // aiming: non-spread ammo is pinpoint
            return 0;
        }
        double raw = s0 * currentPenalty + bloomNow;
        return Math.max(0, raw / Math.max(0.1, hipfireAcc));
    }

    private static double posePenalty(Player player) {
        if (player.isSprinting() || player.isFallFlying()) return 3.0; // ready pose: can't fire anyway
        if (!player.onGround()) return 3.0;
        if (player.isCrouching()) return 0.8;
        if (player.getDeltaMovement().horizontalDistanceSqr() > 0.02) return 1.6;
        return 1.0;
    }

    public static void addBloom(AmmoExtension ext) {
        bloom = Math.min(2.5 * ext.spread, bloom + 0.15 * ext.spread);
        lastShotMs = now();
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}