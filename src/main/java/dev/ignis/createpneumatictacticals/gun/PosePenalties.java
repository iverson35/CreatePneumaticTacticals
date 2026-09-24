package dev.ignis.createpneumatictacticals.gun;


/**
 * Hipfire pose-penalty table, the single source of truth for the hipfire
 * accuracy model. Both consumers MUST read it here:
 * <ul>
 *   <li>server {@code GunFireHandler.spreadDegrees} — the authoritative
 *       pellet spread</li>
 *   <li>client {@code SpreadModel.currentSpread} — the crosshair gap, which
 *       must mirror the server or the reticle lies</li>
 * </ul>
 */
public final class PosePenalties {

    private PosePenalties() {}

    /**
     * Base hipfire multiplier by pose: standing 3, walking/airborne 6,
     * crouching 1.5, sprint/elytra 12. Final spread = ammo S0 * penalty
     * (+bloom), divided by the gun's hipfire_accuracy_multiplier.
     */
    public static double posePenalty(net.minecraft.world.entity.LivingEntity shooter) {
        // sprint-fire (high-ergonomics guns only) is wildly inaccurate
        if (shooter.isSprinting() || shooter.isFallFlying()) return 12.0;
        if (!shooter.onGround()) return 6.0;
        if (shooter.isCrouching()) return 1.5;
        // walking: velocity OR the per-tick walkDist increment (walkDist
        // is the animation driver, guaranteed populated client-side;
        // deltaMovement alone proved unreliable in some setups)
        if (shooter.getDeltaMovement().horizontalDistanceSqr() > 0.02
                || shooter.walkDist - shooter.walkDistO > 0.02f) return 6.0;
        return 3.0;
    }
}