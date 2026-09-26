package dev.ignis.createpneumatictacticals.client;

import dev.ignis.createpneumatictacticals.client.render.GunAnimations;
import dev.ignis.createpneumatictacticals.compat.aw.AwCompat;
import dev.ignis.createpneumatictacticals.client.render.ModuleAnimatable;
import dev.ignis.createpneumatictacticals.gun.GunNbt;
import dev.ignis.createpneumatictacticals.gun.GunStats;
import dev.ignis.createpneumatictacticals.item.GeoGunItem;
import dev.ignis.createpneumatictacticals.module.FeedType;
import dev.ignis.createpneumatictacticals.module.ModuleDefinition;
import dev.ignis.createpneumatictacticals.module.ModuleType;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;

import java.util.HashSet;
import java.util.Set;

/**
 * Drives the "anim" controller of the held gun AND every installed module.
 * Animations broadcast (plan_v3): fire/reload/bolt are triggered on the
 * receiver and on each module's own animation json under the same animation
 * name — modules that don't define it stay silent (filterExisting). E.g.
 * reload cycles the receiver's charge handle while the feed module ejects
 * its magazine; fire can move a bolt on any part that ships one.
 */
public final class GunAnimationDriver {

    public static final String CONTROLLER = "anim";

    private GunAnimationDriver() {}

    /**
     * Reload start: picks reload vs reload_round by the installed feed
     * module's load_type. Broadcast to the receiver and all modules. The
     * empty decision comes from the caller, never from the NBT count: an
     * ammo swap empties the magazine server-side, and that sync is still a
     * tick away — reading it here picked the non-empty chain for every swap.
     *
     * The empty-reload bolt is deliberately NOT a second stage of this
     * RawAnimation: GeckoLib's stage switch polls the next stage inside
     * processCurrentAnimation without re-saving the transition start, so the
     * bolt blended from whatever snapshot the last animation touching the
     * same bone (the fire's slide) had left in the controller — a stale
     * mid-animation pose, visible as the slide jerking backwards first.
     * ClientGunInput triggers onBolt at the bolt's own start instead.
     */
    public static void onReloadStart() {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;
        ItemStack gun = player.getMainHandItem();
        if (!(gun.getItem() instanceof GeoGunItem)) return;
        GunStats stats = GunStats.ofGun(gun);
        if (stats.feed == null) return;
        boolean round = stats.feed.feedType == FeedType.ROUND;
        RawAnimation anim = round ? GunAnimations.RELOAD_ROUND : GunAnimations.RELOAD_MAGAZINE;
        // play the reload at reloadSpeed: the lock window
        // (GunAnimTiming.reloadBatchMs) is the same reload + bolt sequence
        // divided by reloadSpeed, so the animation MUST be sped up
        // identically or the visual tail (bolt) outlives the lock and the
        // gun fires mid-bolt
        broadcast(gun, anim, stats.reloadSpeed, 2);
    }

    /**
     * Empty-reload bolt cycle on all parts, fired at the bolt's own start
     * (ClientGunInput.tickReload) instead of riding the reload chain — see
     * onReloadStart for why the chained stage switch broke the transition
     * start. Scaled by reloadSpeed exactly like the reload it follows.
     */
    public static void onBolt(double speed) {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;
        broadcast(player.getMainHandItem(), GunAnimations.BOLT, speed, 2);
    }

    /**
     * Fire animation (charge handle / bolt cycle / mag feed) on all parts,
     * with a single-tick transition. GeckoLib keeps the controller in
     * TRANSITIONING for transitionLength ticks before the animation runs, so
     * the 2-tick blend the reload/bolt use reads as the whole fire animation
     * lagging a beat behind the shot (sound and the recoil punch are local
     * and land on the trigger tick). 0 would be ideal but is not usable: with
     * a zero length the controller enters RUNNING before polling the queue,
     * and the poll in processCurrentAnimation is then skipped because
     * adjustTick already consumed shouldResetTick — a retrigger replays the
     * previous animation instead of the new one.
     */
    public static void onFire(ItemStack gun) {
        broadcast(gun, GunAnimations.FIRE, 1.0, 1);
    }

    /** receiver + every installed module (each plays the animation only if it defines it) */
    private static void broadcast(ItemStack gun, RawAnimation anim, double speed, int transitionTicks) {
        triggerReceiver(gun, anim, speed, transitionTicks);
        // per-gun-stack module animation instances (mirrors GunModulesLayer)
        long gunId = GeoItem.getId(gun);
        Set<ResourceLocation> seen = new HashSet<>();
        for (ModuleDefinition def : GunNbt.readModules(gun).values()) {
            if (def.type != ModuleType.RECEIVER && seen.add(def.id)) {
                triggerModule(def.id, anim, gunId, speed, transitionTicks);
            }
        }
        for (ModuleDefinition def : GunNbt.readHandguardAttachments(gun).values()) {
            if (seen.add(def.id)) {
                triggerModule(def.id, anim, gunId, speed, transitionTicks);
            }
        }
        // AW skins ride the same broadcast: play() no-ops on skins that
        // don't define the name, so unskinned guns pay nothing (the guard
        // inside is a cheap static boolean when AW is absent)
        if (AwCompat.loaded()) {
            AwCompat.onGunAnimation(gunId, animName(anim), speed);
        }
    }

    /** the single animation name of a RawAnimation built with thenPlay */
    private static String animName(RawAnimation anim) {
        return anim.getAnimationStages().get(0).animationName();
    }

    private static void triggerReceiver(ItemStack gun, RawAnimation anim, double speed, int transitionTicks) {
        if (!(gun.getItem() instanceof GeoGunItem item)) return;
        RawAnimation filtered = GunAnimations.filterExisting(anim,
                dev.ignis.createpneumatictacticals.client.render.GunAssets.forStack(gun).animation());
        long id = GeoItem.getId(gun);
        if (filtered == null) return; // receiver omits this animation: silent
        // Animation names resolve via GunGeoModel.getAnimationResource, which
        // follows the last-rendered stack; pin it to this gun or the lookup
        // can land on another gun / the placeholder (silent no-op stubs)
        dev.ignis.createpneumatictacticals.client.render.GunHandsAwareRenderer.activeModel()
                .withStack(gun, () -> triggerOn("recv", id,
                        item.getAnimatableInstanceCache().getManagerForId(id), filtered, speed, transitionTicks));
    }

    private static void triggerModule(ResourceLocation moduleId, RawAnimation anim, long gunId, double speed,
            int transitionTicks) {
        RawAnimation filtered = GunAnimations.filterExisting(anim, ModuleAnimatable.animationId(moduleId));
        if (filtered == null) return; // module omits this animation: silent
        ModuleAnimatable module = ModuleAnimatable.of(moduleId);
        triggerOn("mod:" + moduleId, gunId, module.getAnimatableInstanceCache().getManagerForId(gunId), filtered,
                speed, transitionTicks);
    }
    private static void triggerOn(String scope, long instanceId, AnimatableManager<? extends GeoAnimatable> manager,
            RawAnimation anim, double speed, int transitionTicks) {
        if (manager == null) return;
        AnimationController<?> controller = manager.getAnimationControllers().get(CONTROLLER);
        if (controller == null) return;
        // per-trigger: 2 ticks for reload/bolt (they blend in from whatever
        // the gun was doing), 1 for fire (see onFire)
        controller.transitionLength(transitionTicks);
        // GeckoLib blends the transition start from its bone-snapshot table,
        // which went stale the moment the previous animation finished (it
        // only updates while an animation is RUNNING). Replay the pose the
        // player actually saw so the transition blends from truth — without
        // this, fire->reload visibly twitches the bolt from the stale
        // mid-animation value before blending back to 0.
        dev.ignis.createpneumatictacticals.client.render.GunAnimations.refreshSnapshotsFromLivePose(scope, instanceId, manager);
        controller.setAnimationSpeed(Math.max(0.1, speed));
        // forceAnimationReset only marks the controller for reload
        // (needsAnimationReload); setAnimation still enters the normal
        // TRANSITIONING path. It is required when REPLAYING the same
        // animation (rapid semi-auto fire) because setAnimation would
        // otherwise treat the equal RawAnimation as "already playing" and
        // keep the old run. For a different animation it is skipped purely
        // to keep the transition blending from the refreshed pose above.
        if (anim.equals(controller.getCurrentRawAnimation())) {
            controller.forceAnimationReset();
        }
        controller.setAnimation(anim);
    }

    /**
     * Hard interrupt (slot switch / screen opened mid-reload): stops the
     * one-shot controller on the receiver and every installed module, then
     * snaps all poses back to rest. forceAnimationReset + a zero-length wait
     * is the clean stop: stop() alone gets revived by the CONTINUE predicate
     * and a bare forceAnimationReset replays the queued animation.
     */
    public static void interrupt(ItemStack gun) {
        if (!(gun.getItem() instanceof GeoGunItem item)) return;
        long gunId = GeoItem.getId(gun);
        stopOn(item.getAnimatableInstanceCache().getManagerForId(gunId));
        dev.ignis.createpneumatictacticals.client.render.GunGeoModel gunModel =
                dev.ignis.createpneumatictacticals.client.render.GunHandsAwareRenderer.activeModel();
        if (gunModel != null) {
            gunModel.withStack(gun, () -> {
                gunModel.getBakedModel(gunModel.getModelResource(item)); // activate receiver bones
                GunAnimations.resetToRestPose(gunModel);
            });
        }
        for (ModuleDefinition def : GunNbt.readModules(gun).values()) {
            if (def.type != ModuleType.RECEIVER) interruptModule(def.id, gunId);
        }
        for (ModuleDefinition def : GunNbt.readHandguardAttachments(gun).values()) {
            interruptModule(def.id, gunId);
        }
        // AW skins stop with the same event (cheap guard when absent)
        AwCompat.interruptGunAnimations(gunId);
    }

    private static void interruptModule(ResourceLocation moduleId, long gunId) {
        stopOn(ModuleAnimatable.of(moduleId).getAnimatableInstanceCache().getManagerForId(gunId));
        dev.ignis.createpneumatictacticals.client.render.ModuleGunGeoModel.INSTANCE.resetPose(moduleId);
    }

    private static final RawAnimation STOP = RawAnimation.begin().thenWait(0);

    private static void stopOn(AnimatableManager<? extends GeoAnimatable> manager) {
        if (manager == null) return;
        AnimationController<?> controller = manager.getAnimationControllers().get(CONTROLLER);
        if (controller == null) return;
        controller.forceAnimationReset();
        controller.setAnimation(STOP);
    }
}
