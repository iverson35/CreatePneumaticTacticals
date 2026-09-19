package dev.ignis.createpneumatictacticals.client;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Muzzle-obstruction check: a block within {@link #RANGE} of the eye along
 * the view ray means the muzzle is pressed into geometry — firing and
 * aiming are locked and the gun is forced into the ready pose.
 *
 * <p>One-way debounce: unblocked -> blocked requires the ray to stay
 * obstructed for {@link #DEBOUNCE_MS} continuously (swinging past a corner
 * doesn't flicker); blocked -> unblocked releases instantly.
 */
public final class MuzzleClearance {

    private static final long DEBOUNCE_MS = 125;

    private static boolean blocked = false;
    /** when the current continuous obstruction stretch started; -1 = clear */
    private static long firstBlockedMs = -1;

    private MuzzleClearance() {}

    /** client-tick raycast; call once per tick (holdingGun=false resets) */
    public static void tick(Player player, boolean holdingGun) {
        if (!holdingGun) {
            blocked = false;
            firstBlockedMs = -1;
            return;
        }
        boolean raw;
        if (player.isSpectator()) {
            raw = false;
        } else {
            var eye = player.getEyePosition();
            // dynamic gun length: a suppressor pushes the muzzle further out,
            // so the obstruction ray must reach the actual muzzle tip
            double range = dev.ignis.createpneumatictacticals.gun.GunLength.of(player.getMainHandItem());
            var end = eye.add(player.getViewVector(1.0f).scale(range));
            BlockHitResult hit = player.level().clip(new ClipContext(
                    eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            raw = hit.getType() != BlockHitResult.Type.MISS;
        }
        long now = System.currentTimeMillis();
        if (raw) {
            if (firstBlockedMs < 0) firstBlockedMs = now;
            if (!blocked && now - firstBlockedMs >= DEBOUNCE_MS) blocked = true;
        } else {
            firstBlockedMs = -1;
            blocked = false; // one-way debounce: release is instant
        }
    }

    /** the debounced obstruction state; gates firing, aiming and the ready pose */
    public static boolean isBlocked() {
        return blocked;
    }
}
