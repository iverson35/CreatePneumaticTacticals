package dev.ignis.createpneumatictacticals.client.render;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import software.bernie.geckolib.cache.GeckoLibCache;
import software.bernie.geckolib.core.animation.Animation;
import software.bernie.geckolib.loading.object.BakedAnimations;

/**
 * Reload timing derived from the receiver's animation lengths (plan: 换弹时间
 * 按机匣动画时长，空仓追加拉栓动画). Falls back to vanilla-ish defaults while
 * assets are missing. All values scaled by the reload_speed multiplier.
 */
public final class GunAnimTiming {

    private static final double FALLBACK_RELOAD_TICKS = 50;   // 2.5s magazine
    private static final double FALLBACK_ROUND_TICKS = 16;    // 0.8s per round batch
    private static final double FALLBACK_BOLT_TICKS = 10;     // 0.5s bolt cycle

    private GunAnimTiming() {}

    /** reload PHASE only (no pre-bolt, no bolt, no transitions) — when the
     *  third-person choreography should start its up-swing, aligned with
     *  the bolt start */
    public static long reloadPhaseMs(ItemStack gun, boolean round, double reloadSpeed) {
        double ticks = animLengthTicks(gun, round ? "reload_round" : "reload",
                round ? FALLBACK_ROUND_TICKS : FALLBACK_RELOAD_TICKS);
        return (long) (ticks * 50 / Math.max(0.1, reloadSpeed));
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
        return (long) (ticks * 50.0 / Math.max(0.1, reloadSpeed));
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