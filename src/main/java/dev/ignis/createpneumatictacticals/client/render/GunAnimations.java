package dev.ignis.createpneumatictacticals.client.render;

import software.bernie.geckolib.core.animation.RawAnimation;

/**
 * Animation name constants per receiver (plan_v2): "idle" loop; "fire"
 * (charge handle cycle, one-shot per trigger pull); "reload" (magazine
 * feed); "reload_round" (per-round feed — receiver defines BOTH reload
 * animations and the installed feed module's load_type picks which one
 * plays); "bolt" (empty-reload bolt cycle). Sound keyframes are authored
 * in the animation json (sound_effects) and played via GeckoLib's
 * SoundKeyframeHandler (software.bernie.geckolib.core.keyframe.event.SoundKeyframeEvent).
 */
public final class GunAnimations {

    public static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    public static final RawAnimation FIRE = RawAnimation.begin().thenPlay("fire");
    public static final RawAnimation RELOAD_MAGAZINE = RawAnimation.begin().thenPlay("reload");
    public static final RawAnimation RELOAD_ROUND = RawAnimation.begin().thenPlay("reload_round");
    public static final RawAnimation BOLT = RawAnimation.begin().thenPlay("bolt");

    private GunAnimations() {}
}