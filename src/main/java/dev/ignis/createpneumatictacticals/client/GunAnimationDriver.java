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

    /** fire animation (charge handle / bolt cycle / mag feed) on all parts */
    public static void onFire(ItemStack gun) {
        broadcast(gun, GunAnimations.FIRE);
    }

    /**
     * Reload start: picks reload vs reload_round by the installed feed
     * module's load_type; an empty magazine appends the bolt cycle (timing
     * mirrors GunAnimTiming). Broadcast to the receiver and all modules.
     */
    public static void onReloadStart() {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;
        ItemStack gun = player.getMainHandItem();
        if (!(gun.getItem() instanceof GeoGunItem)) return;
        GunStats stats = GunStats.ofGun(gun);
        if (stats.feed == null) return;
        boolean round = stats.feed.feedType == FeedType.ROUND;
        boolean empty = GunNbt.getAmmoCount(gun) <= 0;
        RawAnimation anim = round ? GunAnimations.RELOAD_ROUND : GunAnimations.RELOAD_MAGAZINE;
        if (empty) {
            anim = RawAnimation.begin()
                    .thenPlay(round ? "reload_round" : "reload")
                    .thenPlay("bolt");
        }
        broadcast(gun, anim);
    }

    /** empty-reload bolt cycle on all parts */
    public static void onBolt() {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;
        broadcast(player.getMainHandItem(), GunAnimations.BOLT);
    }

    /** receiver + every installed module (each plays the animation only if it defines it) */
    private static void broadcast(ItemStack gun, RawAnimation anim) {
        triggerReceiver(gun, anim);
        // per-gun-stack module animation instances (mirrors GunModulesLayer)
        long gunId = GeoItem.getId(gun);
        Set<ResourceLocation> seen = new HashSet<>();
        for (ModuleDefinition def : GunNbt.readModules(gun).values()) {
            if (def.type != ModuleType.RECEIVER && seen.add(def.id)) {
                triggerModule(def.id, anim, gunId);
            }
        }
        for (ModuleDefinition def : GunNbt.readHandguardAttachments(gun).values()) {
            if (seen.add(def.id)) {
                triggerModule(def.id, anim, gunId);
            }
        }
    }

    private static void triggerReceiver(ItemStack gun, RawAnimation anim) {
        if (!(gun.getItem() instanceof GeoGunItem item)) return;
        RawAnimation filtered = GunAnimations.filterExisting(anim,
                dev.ignis.createpneumatictacticals.client.render.GunAssets.forStack(gun).animation());
        if (filtered == null) return; // receiver omits this animation: silent
        triggerOn(item, item.getAnimatableInstanceCache().getManagerForId(GeoItem.getId(gun)), filtered);
    }

    private static void triggerModule(ResourceLocation moduleId, RawAnimation anim, long gunId) {
        RawAnimation filtered = GunAnimations.filterExisting(anim, ModuleAnimatable.animationId(moduleId));
        if (filtered == null) return; // module omits this animation: silent
        ModuleAnimatable module = ModuleAnimatable.of(moduleId);
        triggerOn(module, module.getAnimatableInstanceCache().getManagerForId(gunId), filtered);
    }

    private static void triggerOn(GeoAnimatable animatable, AnimatableManager<? extends GeoAnimatable> manager, RawAnimation anim) {
        if (manager == null) return;
        AnimationController<?> controller = manager.getAnimationControllers().get(CONTROLLER);
        if (controller == null) return;
        controller.forceAnimationReset();
        controller.setAnimation(anim);
    }
}
