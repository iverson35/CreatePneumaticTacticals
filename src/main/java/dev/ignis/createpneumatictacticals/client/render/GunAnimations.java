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

    /** does this animation file contain the named animation */
    public static boolean hasAnimation(net.minecraft.resources.ResourceLocation animationFile, String name) {
        software.bernie.geckolib.loading.object.BakedAnimations baked =
                software.bernie.geckolib.cache.GeckoLibCache.getBakedAnimations().get(animationFile);
        return baked != null && baked.getAnimation(name) != null;
    }

    /**
     * Keep only the stages whose animation exists in the file; null when
     * nothing survives. Guns are allowed to omit animations — a missing one
     * must stay silent instead of spamming "Unable to find animation".
     */
    /**
     * Snaps every registered bone back to its baked rest pose. Needed when
     * animations are suppressed (inventory icons, dropped guns) or cancelled
     * mid-play: GeckoLib leaves animated bone values in the shared baked
     * model, so without a reset the last animated pose sticks forever.
     */
    public static void resetToRestPose(software.bernie.geckolib.model.GeoModel<?> model) {
        for (software.bernie.geckolib.core.animatable.model.CoreGeoBone bone
                : model.getAnimationProcessor().getRegisteredBones()) {
            software.bernie.geckolib.core.state.BoneSnapshot snap = bone.getInitialSnapshot();
            bone.setPosX(snap.getOffsetX());
            bone.setPosY(snap.getOffsetY());
            bone.setPosZ(snap.getOffsetZ());
            bone.setRotX(snap.getRotX());
            bone.setRotY(snap.getRotY());
            bone.setRotZ(snap.getRotZ());
            bone.setScaleX(snap.getScaleX());
            bone.setScaleY(snap.getScaleY());
            bone.setScaleZ(snap.getScaleZ());
        }
    }
    /**
     * Captures pos/rot/scale of every currently-registered bone, keyed by the
     * bone OBJECT (not name/index): the shared processor's registered set
     * changes as other modules activate their baked models mid-pass.
     */
    public static java.util.Map<software.bernie.geckolib.core.animatable.model.CoreGeoBone, float[]> snapshotBones(
            software.bernie.geckolib.model.GeoModel<?> model) {
        java.util.Map<software.bernie.geckolib.core.animatable.model.CoreGeoBone, float[]> out = new java.util.HashMap<>();
        for (software.bernie.geckolib.core.animatable.model.CoreGeoBone bone
                : model.getAnimationProcessor().getRegisteredBones()) {
            out.put(bone, new float[]{bone.getPosX(), bone.getPosY(), bone.getPosZ(),
                    bone.getRotX(), bone.getRotY(), bone.getRotZ(),
                    bone.getScaleX(), bone.getScaleY(), bone.getScaleZ()});
        }
        return out;
    }

    /** restores a snapshotBones capture onto the same bone objects */
    public static void restoreBones(java.util.Map<software.bernie.geckolib.core.animatable.model.CoreGeoBone, float[]> snapshot) {
        for (java.util.Map.Entry<software.bernie.geckolib.core.animatable.model.CoreGeoBone, float[]> e : snapshot.entrySet()) {
            software.bernie.geckolib.core.animatable.model.CoreGeoBone bone = e.getKey();
            float[] v = e.getValue();
            bone.setPosX(v[0]); bone.setPosY(v[1]); bone.setPosZ(v[2]);
            bone.setRotX(v[3]); bone.setRotY(v[4]); bone.setRotZ(v[5]);
            bone.setScaleX(v[6]); bone.setScaleY(v[7]); bone.setScaleZ(v[8]);
        }
    }

    public static RawAnimation filterExisting(RawAnimation anim, net.minecraft.resources.ResourceLocation animationFile) {
        RawAnimation out = null;
        for (RawAnimation.Stage stage : anim.getAnimationStages()) {
            if (!hasAnimation(animationFile, stage.animationName())) continue;
            out = out == null
                    ? RawAnimation.begin().then(stage.animationName(), stage.loopType())
                    : out.then(stage.animationName(), stage.loopType());
        }
        return out;
    }
}