package dev.ignis.createpneumatictacticals.client;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Low/high ready pose state machine (plan_v2): sprinting or elytra flying
 * with a gun holsters it into the ready pose; after stopping, the gun needs
 * {@link #READY_DELAY_MS} (scaled by ergonomics) to return to the firing
 * stance — firing is locked until fully recovered. That lock is client-side
 * on purpose: the server's authoritative answer to a pose is the spread
 * table, and this gate only exists so the gun cannot shoot while it is still
 * visibly coming up. Aiming suppresses the
 * pose (aim wins), and so does a blocked muzzle.
 *
 * <p>Drawing a gun counts as a stow too: switching the main hand onto a gun
 * starts it in low ready, so it comes up over the same recovery instead of
 * firing the instant it appears.
 *
 * <p>Sprint-fire guns (ergonomics &gt; SPRINT_FIRE_ERGO) rest in the ready
 * pose while running as well, but holding attack — or a shot within the
 * last second — raises the gun over the recovery time (that recovery is the
 * sprint-fire delay); one second without firing drops it back to ready.
 *
 * <p>Sluggish guns (ergonomics &lt;= SPRINT_FIRE_ERGO) may only sprint FROM
 * the ready pose: pressing fire while running raises the gun and fires (the
 * same engaging path as the sprint-fire guns), but the firing stance pins
 * the shooter to a walk — sprinting is denied until the same one-second
 * window drops the gun back to ready, at which point running resumes. So a
 * sluggish gun trades "run and gun" for "stop to shoot". The sprint denial
 * itself is enforced by the LocalPlayer sprint mixin ({@link #blocksSprint(Player)}),
 * not by the movement input: the walk speed stays untouched.
 *
 * <p>Tick-driven with partial-tick lerp, mirroring AimHandler.
 */
public final class ReadyModel {

    private static final float HOLSTER_MS = 150f;

    /** base recovery delay (ms) before the gun can fire again after the ready
     * pose; divided by the gun's ergonomics and clamped to 1/3..4x below */
    private static final float READY_DELAY_MS = 250f;
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
    /** main-hand item + selected hotbar slot last tick; a change onto a gun
     *  is a draw (the slot is part of the key so swapping between two
     *  identical guns in different slots counts as a draw too) */
    private static Item lastHeldItem;
    private static int lastHeldSlot = -1;

    private ReadyModel() {}
    public static void tick(Player player, boolean holdingGun) {
        prevProgress = progress;
        prevHighMix = highMix;
        // Draw: the main hand just changed onto a gun (hotbar switch, picking
        // one up, login). Start in low ready and let the recovery below bring
        // it up — the draw tick itself only sets the pose, so the full delay
        // still runs from the next tick.
        Item held = player.getMainHandItem().getItem();
        int slot = player.getInventory().selected;
        if (holdingGun && (held != lastHeldItem || slot != lastHeldSlot)) {
            lastHeldItem = held;
            lastHeldSlot = slot;
            progress = 1f;
            prevProgress = 1f; // snap into the pose: the gun only just appeared
            stowed = true;     // reads as low ready this tick (pose broadcast)
            highReady = false;
            highMix = 0f;
            prevHighMix = 0f;
            return;
        }
        lastHeldItem = held;
        lastHeldSlot = slot;
        double ergo = holdingGun
                ? dev.ignis.createpneumatictacticals.gun.GunStats.ergoScale(player.getMainHandItem())
                : 1.0;
        stowed = computeStowed(player, holdingGun);
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
            // takes READY_DELAY_MS / ergo, clamped to 1/3..4x the base delay
            float recoveryMs = (float) (READY_DELAY_MS / ergo);
            progress = Math.max(0f, progress - TICK_MS / Math.max(1f, recoveryMs));
        }
    }

    /**
     * The ready-pose decision, shared by the tick (pose state) and the live
     * sprint check below.
     *
     * <p>Releasing aim while still holding the sprint key: vanilla drops the
     * sprint flag for a couple of ticks, so isSprinting() alone would let the
     * gun fall ALL the way to hipfire before the ready pose kicks back in — a
     * visible down-then-up jerk, worst in high ready. Treat the sprint key
     * held + moving forward as sprint intent while the ADS fade-out still
     * runs, so the ready overlay takes over seamlessly.
     *
     * <p>Engaging raises the gun out of the ready pose for every gun class:
     * for sprint-fire guns (high ergonomics) that is the sprint-fire delay,
     * for sluggish ones the "plant before you fire" rule — the shot only
     * leaves once the gun is back in the firing stance, and the shooter
     * cannot sprint until the same delay drops it back to ready.
     */
    private static boolean computeStowed(Player player, boolean holdingGun) {
        if (!holdingGun || AimHandler.isAiming()) return false;
        // elytra always stows; a blocked muzzle forces the ready pose
        // regardless of ergonomics
        if (player.isFallFlying() || MuzzleClearance.isBlocked()) return true;
        Minecraft mc = Minecraft.getInstance();
        boolean sprintStow = player.isSprinting()
                || (mc.options.keySprint.isDown() && mc.options.keyUp.isDown());
        if (sprintStow && isEngaging()) {
            return false;
        }
        return sprintStow;
    }

    /** holding attack, or a shot within the last second */
    private static boolean isEngaging() {
        return Minecraft.getInstance().options.keyAttack.isDown()
                || System.currentTimeMillis() - ClientGunInput.lastShotMs() < 1000;
    }

    /**
     * Live sprint denial for the LocalPlayer sprint mixin: a sluggish gun
     * (ergonomics &lt;= SPRINT_FIRE_ERGO) may only sprint FROM the ready pose,
     * so the firing stance — aiming, attack held, a shot within the last
     * second — denies it. Evaluated on the spot (not from the tick cache) so
     * a sprint started this tick counts: the sprint key, a double-tap W and
     * an already-running sprint all put the gun into the ready pose and are
     * allowed; only sprinting with the gun ENGAGED is denied. High-ergonomics
     * guns are exempt — they sprint-fire.
     */
    public static boolean blocksSprint(Player player) {
        ItemStack gun = player.getMainHandItem();
        if (!(gun.getItem() instanceof dev.ignis.createpneumatictacticals.item.GeoGunItem)) return false;
        if (dev.ignis.createpneumatictacticals.gun.GunStats.ergoScale(gun)
                > dev.ignis.createpneumatictacticals.gun.GunStats.SPRINT_FIRE_ERGO) return false;
        return !computeStowed(player, true);
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
