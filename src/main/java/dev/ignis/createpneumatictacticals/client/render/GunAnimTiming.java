package dev.ignis.createpneumatictacticals.client.render;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import software.bernie.geckolib.cache.GeckoLibCache;
import software.bernie.geckolib.core.animation.Animation;
import software.bernie.geckolib.loading.object.BakedAnimations;
import dev.ignis.createpneumatictacticals.gun.GunStats;

/**
 * Reload timing derived from the receiver's animation lengths (plan: 换弹时间
 * 按机匣动画时长，空仓追加拉栓动画). Falls back to vanilla-ish defaults while
 * assets are missing. All values scaled by the reload_speed multiplier.
 */
public final class GunAnimTiming {

    private static final double FALLBACK_RELOAD_TICKS = 50;   // 2.5s magazine
    private static final double FALLBACK_ROUND_TICKS = 16;    // 0.8s per round batch
    private static final double FALLBACK_BOLT_TICKS = 10;     // 0.5s bolt cycle

    /** fire playback speed clamp — only a sanity rail against degenerate
     *  values: wide enough that every realistic gun+ammo pair lands on the
     *  exact "one shot interval per animation" ratio (1200 RPM + a 1.25s fire
     *  cycle still fits), narrow enough that a broken asset can't produce a
     *  frozen pose or a single-frame blur */
    public static final double FIRE_SPEED_MIN = 0.05;
    public static final double FIRE_SPEED_MAX = 16.0;

    private GunAnimTiming() {}

    /**
     * Playback speed for the fire animation: the receiver's authored fire
     * length (the anchor — modules and AW skins ride the same number) is
     * stretched or squeezed over the current shot interval, so one fire
     * animation spans exactly one gun+ammo cycle and the cycle is never cut
     * off mid-swap by the next shot. intervalTicks is
     * {@code AmmoExtension.fireIntervalTicks} — the same number that gates the
     * shot itself, so animation and cadence can't drift apart.
     *
     * <p>No receiver fire animation (or an unknown interval) means nothing to
     * anchor: 1×. Clamped to [{@link #FIRE_SPEED_MIN}, {@link #FIRE_SPEED_MAX}]
     * — a sanity rail only, so every realistic cadence (1200 RPM ammo with a
     * 1.25s fire cycle included) gets the exact ratio.
     */
    public static double fireSpeed(ItemStack gun, long intervalTicks) {
        double fireTicks = animLengthTicks(gun, "fire", 0);
        if (fireTicks <= 0 || intervalTicks <= 0) return 1.0;
        return Math.max(FIRE_SPEED_MIN, Math.min(FIRE_SPEED_MAX, fireTicks / intervalTicks));
    }

    /** reload PHASE only (no pre-bolt, no bolt, no transitions) — when the
     *  third-person choreography should start its up-swing, aligned with
     *  the bolt start */
    public static long reloadPhaseMs(ItemStack gun, boolean round, double reloadSpeed) {
        double ticks = animLengthTicks(gun, round ? "reload_round" : "reload",
                round ? FALLBACK_ROUND_TICKS : FALLBACK_RELOAD_TICKS);
        return (long) (ticks * 50 / Math.max(GunStats.RELOAD_SPEED_MIN, reloadSpeed));
    }

    /**
     * Duration of one reload batch in milliseconds. Round mode: one batch =
     * one reload_round animation. Empty magazine plays the full empty chain
     * pre_bolt → reload → bolt (pre_bolt only when the receiver's animation
     * file defines it — a missing one adds nothing; the bolt's fallback
     * length applies when the file lacks it, exactly the pre-pre_bolt
     * behavior).
     */
    public static long reloadBatchMs(ItemStack gun, boolean round, boolean empty, double reloadSpeed) {
        double ticks = animLengthTicks(gun, round ? "reload_round" : "reload",
                round ? FALLBACK_ROUND_TICKS : FALLBACK_RELOAD_TICKS);
        if (empty) {
            double pre = preBoltTicks(gun);
            ticks += pre;
            ticks += animLengthTicks(gun, "bolt", FALLBACK_BOLT_TICKS);
            // one 2-tick GeckoLib transition per stage start (pre-bolt /
            // reload / bolt; the bolt always counts — its fallback length
            // applies when the file lacks it). 4 ticks for the classic
            // two-stage reload (unchanged), 6 with a pre_bolt.
            ticks += 2 * (2 + (pre > 0 ? 1 : 0));
        }
        return (long) (ticks * 50.0 / Math.max(GunStats.RELOAD_SPEED_MIN, reloadSpeed));
    }

    /**
     * pre_bolt stage length in ticks, 0 when the receiver's animation file
     * does not define pre_bolt. Deliberately NOT animLengthTicks with a
     * fallback: a missing pre_bolt must add no time at all (the fallback
     * path exists for guns with NO animation file, where the whole reload
     * needs SOME length).
     */
    public static double preBoltTicks(ItemStack gun) {
        if (!hasNamedAnimation(gun, "pre_bolt")) return 0;
        return animLengthTicks(gun, "pre_bolt", 0);
    }

    /** does the receiver's animation file define the named animation? */
    public static boolean hasNamedAnimation(ItemStack gun, String name) {
        ResourceLocation file = GunAssets.forStack(gun).animation();
        BakedAnimations baked = GeckoLibCache.getBakedAnimations().get(file);
        return baked != null && GunAnimations.resolve(baked, name) != null;
    }

    /** animation length in ticks from the gun's current animation file */
    public static double animLengthTicks(ItemStack gun, String name, double fallbackTicks) {
        ResourceLocation file = GunAssets.forStack(gun).animation();
        BakedAnimations baked = GeckoLibCache.getBakedAnimations().get(file);
        if (baked == null) return fallbackTicks;
        // Blockbench exports may key animations by full name; resolve first
        Animation anim = baked.getAnimation(GunAnimations.resolve(baked, name));
        return anim != null && anim.length() > 0 ? anim.length() : fallbackTicks;
    }
}