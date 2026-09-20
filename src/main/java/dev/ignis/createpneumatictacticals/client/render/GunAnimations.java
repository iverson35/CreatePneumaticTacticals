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

    /**
     * Resolves the code-side bare animation name ("reload") to the key in
     * the baked file. Blockbench exports animations with a full name
     * ("animation.mak_1_receiver.reload"), so when the bare name is absent,
     * fall back to a unique ".<name>" suffix match. Ambiguous suffixes
     * (e.g. both "reload" and "reload_round" would match ".reload" if the
     * file had "a.reload") resolve to the first match in file order.
     */
    @org.jetbrains.annotations.Nullable
    public static String resolve(net.minecraft.resources.ResourceLocation animationFile, String bareName) {
        software.bernie.geckolib.loading.object.BakedAnimations baked =
                software.bernie.geckolib.cache.GeckoLibCache.getBakedAnimations().get(animationFile);
        return baked == null ? null : resolve(baked, bareName);
    }

    @org.jetbrains.annotations.Nullable
    public static String resolve(software.bernie.geckolib.loading.object.BakedAnimations baked, String bareName) {
        if (baked.getAnimation(bareName) != null) return bareName;
        // Blockbench full names: "<anything>.<bareName>"
        String suffix = "." + bareName;
        for (String key : baked.animations().keySet()) {
            if (key.endsWith(suffix)) return key;
        }
        return null;
    }

    /** does this animation file contain the named animation */
    public static boolean hasAnimation(net.minecraft.resources.ResourceLocation animationFile, String name) {
        return resolve(animationFile, name) != null;
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

    /**
     * Captures the live first-person bone pose, keyed by bone NAME per
     * animatable-instance id, so it can be replayed into GeckoLib's stale
     * snapshot table right before a new animation triggers.
     *
     * GeckoLib blends the start of a transition from
     * AnimatableManager.getBoneSnapshotCollection() — a table that only
     * updates while an animation is RUNNING. Once an animation finishes the
     * table goes stale (it still holds the last polled mid-animation value),
     * even though the live bones have already been reset. Triggering a new
     * animation then blends from that dead value and the model visibly
     * twitches. Replaying the pose the player actually sees makes the
     * transition start from truth.
     */
    // scope|instanceId -> bone-name -> [pos,rot,scale]; receiver uses "recv",
    // modules "mod:<moduleId>" — receiver and modules share the same gun
    // instance id, so the scope keeps their captures from overwriting
    // each other.
    public static final java.util.Map<String, java.util.Map<String, float[]>> LIVE_POSE = new java.util.concurrent.ConcurrentHashMap<>();

    /** capture pose of every registered bone (call on the first-person render pass, after animations are applied) */
    public static void captureLivePose(String scope, software.bernie.geckolib.model.GeoModel<?> model, long instanceId) {
        java.util.Map<String, float[]> pose = new java.util.HashMap<>();
        for (software.bernie.geckolib.core.animatable.model.CoreGeoBone bone
                : model.getAnimationProcessor().getRegisteredBones()) {
            pose.put(bone.getName(), new float[]{
                    bone.getPosX(), bone.getPosY(), bone.getPosZ(),
                    bone.getRotX(), bone.getRotY(), bone.getRotZ(),
                    bone.getScaleX(), bone.getScaleY(), bone.getScaleZ()});
        }
        LIVE_POSE.put(scope + "|" + instanceId, pose);
    }

    /**
     * Replays the captured pose into the animatable's snapshot table, so
     * the next transition blends from the pose the player actually saw
     * instead of GeckoLib's stale post-animation snapshot. Call immediately
     * before triggering a new animation.
     */
    public static void refreshSnapshotsFromLivePose(String scope, long instanceId,
            software.bernie.geckolib.core.animation.AnimatableManager<? extends software.bernie.geckolib.core.animatable.GeoAnimatable> manager) {
        java.util.Map<String, float[]> pose = LIVE_POSE.get(scope + "|" + instanceId);
        if (pose == null || manager == null) return;
        java.util.Map<String, software.bernie.geckolib.core.state.BoneSnapshot> table =
                manager.getBoneSnapshotCollection();
        for (java.util.Map.Entry<String, float[]> e : pose.entrySet()) {
            software.bernie.geckolib.core.state.BoneSnapshot snap = table.get(e.getKey());
            if (snap == null) continue;
            float[] v = e.getValue();
            snap.updateRotation(v[3], v[4], v[5]);
            snap.updateOffset(v[0], v[1], v[2]);
            snap.updateScale(v[6], v[7], v[8]);
        }
    }

    public static RawAnimation filterExisting(RawAnimation anim, net.minecraft.resources.ResourceLocation animationFile) {
        RawAnimation out = null;
        for (RawAnimation.Stage stage : anim.getAnimationStages()) {
            String resolved = resolve(animationFile, stage.animationName());
            if (resolved == null) continue;
            out = out == null
                    ? RawAnimation.begin().then(resolved, stage.loopType())
                    : out.then(resolved, stage.loopType());
        }
        return out;
    }
}