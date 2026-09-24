package dev.ignis.createpneumatictacticals.client;

import dev.ignis.createpneumatictacticals.client.render.GunAnimations;
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
     * module's load_type; {@code empty} appends the bolt cycle (timing mirrors
     * GunAnimTiming). Broadcast to the receiver and all modules. The empty
     * decision comes from the caller, never from the NBT count: an ammo swap
     * empties the magazine server-side, and that sync is still a tick away —
     * reading it here picked the non-empty chain for every swap.
     */
    public static void onReloadStart(boolean empty) {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;
        ItemStack gun = player.getMainHandItem();
        if (!(gun.getItem() instanceof GeoGunItem)) return;
        GunStats stats = GunStats.ofGun(gun);
        if (stats.feed == null) return;
        boolean round = stats.feed.feedType == FeedType.ROUND;
        RawAnimation anim = round ? GunAnimations.RELOAD_ROUND : GunAnimations.RELOAD_MAGAZINE;
        if (empty) {
            anim = RawAnimation.begin()
                    .thenPlay(round ? "reload_round" : "reload")
                    .thenPlay("bolt");
        }
        // play the reload chain at reloadSpeed: the lock window
        // (GunAnimTiming.reloadBatchMs) is the same chain divided by
        // reloadSpeed, so the animation MUST be sped up identically or the
        // visual tail (bolt) outlives the lock and the gun fires mid-bolt
        broadcast(gun, anim, stats.reloadSpeed);
    }

    /** empty-reload bolt cycle on all parts */
    public static void onBolt() {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;
        broadcast(player.getMainHandItem(), GunAnimations.BOLT, 1.0);
    }

    /** fire animation (charge handle / bolt cycle / mag feed) on all parts */
    public static void onFire(ItemStack gun) {
        broadcast(gun, GunAnimations.FIRE, 1.0);
    }

    /** receiver + every installed module (each plays the animation only if it defines it) */
    private static void broadcast(ItemStack gun, RawAnimation anim, double speed) {
        triggerReceiver(gun, anim, speed);
        // per-gun-stack module animation instances (mirrors GunModulesLayer)
        long gunId = GeoItem.getId(gun);
        Set<ResourceLocation> seen = new HashSet<>();
        for (ModuleDefinition def : GunNbt.readModules(gun).values()) {
            if (def.type != ModuleType.RECEIVER && seen.add(def.id)) {
                triggerModule(def.id, anim, gunId, speed);
            }
        }
        for (ModuleDefinition def : GunNbt.readHandguardAttachments(gun).values()) {
            if (seen.add(def.id)) {
                triggerModule(def.id, anim, gunId, speed);
            }
        }
    }

    private static void triggerReceiver(ItemStack gun, RawAnimation anim, double speed) {
        if (!(gun.getItem() instanceof GeoGunItem item)) return;
        RawAnimation filtered = GunAnimations.filterExisting(anim,
                dev.ignis.createpneumatictacticals.client.render.GunAssets.forStack(gun).animation());
        long id = GeoItem.getId(gun);
        if (filtered == null) return; // receiver omits this animation: silent
        // Animation names resolve via GunGeoModel.getAnimationResource, which
        // follows the last-rendered stack; pin it to this gun or the lookup
        // can land on another gun / the placeholder (silent no-op stubs)
        dev.ignis.createpneumatictacticals.client.render.GunHandsAwareRenderer.activeModel()
                .withStack(gun, () -> triggerOn("recv", id, item.getAnimatableInstanceCache().getManagerForId(id), filtered, speed));
    }

    private static void triggerModule(ResourceLocation moduleId, RawAnimation anim, long gunId, double speed) {
        RawAnimation filtered = GunAnimations.filterExisting(anim, ModuleAnimatable.animationId(moduleId));
        if (filtered == null) return; // module omits this animation: silent
        ModuleAnimatable module = ModuleAnimatable.of(moduleId);
        triggerOn("mod:" + moduleId, gunId, module.getAnimatableInstanceCache().getManagerForId(gunId), filtered, speed);
    }
    private static void triggerOn(String scope, long instanceId, AnimatableManager<? extends GeoAnimatable> manager, RawAnimation anim, double speed) {
        if (manager == null) return;
        AnimationController<?> controller = manager.getAnimationControllers().get(CONTROLLER);
        if (controller == null) return;
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
