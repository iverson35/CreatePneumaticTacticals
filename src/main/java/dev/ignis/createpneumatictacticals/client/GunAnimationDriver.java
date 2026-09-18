package dev.ignis.createpneumatictacticals.client;

import dev.ignis.createpneumatictacticals.client.render.GunAnimations;
import dev.ignis.createpneumatictacticals.client.render.ModuleAnimatable;
import dev.ignis.createpneumatictacticals.gun.GunNbt;
import dev.ignis.createpneumatictacticals.gun.GunStats;
import dev.ignis.createpneumatictacticals.item.GeoGunItem;
import dev.ignis.createpneumatictacticals.module.FeedType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;

/**
 * Drives the "anim" controller of the held gun AND its installed modules.
 * Reload is a cross-model animation: the receiver animates its own bones
 * (charge handle) while the feed module animates its own bones (magazine
 * eject / per-round loading) — both trigger the same animation name from
 * their respective animation jsons. fire/bolt are receiver-only (charge
 * handle lives on the receiver).
 */
public final class GunAnimationDriver {

    public static final String CONTROLLER = "anim";

    private GunAnimationDriver() {}

    /** fire animation (charge handle cycle), receiver-only */
    public static void onFire(ItemStack gun) {
        triggerReceiver(gun, GunAnimations.FIRE);
    }

    /**
     * Reload start: picks reload vs reload_round by the installed feed
     * module's load_type, then triggers the receiver (charge handle) and the
     * feed module (magazine / per-round) animations simultaneously.
     */
    public static void onReloadStart() {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;
        ItemStack gun = player.getMainHandItem();
        if (!(gun.getItem() instanceof GeoGunItem)) return;
        GunStats stats = GunStats.of(GunNbt.readModules(gun));
        if (stats.feed == null) return;
        RawAnimation anim = stats.feed.feedType == FeedType.ROUND
                ? GunAnimations.RELOAD_ROUND
                : GunAnimations.RELOAD_MAGAZINE;
        triggerReceiver(gun, anim);
        triggerModule(stats.feed.id, anim);
    }

    /** empty-reload bolt cycle, receiver-only */
    public static void onBolt() {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;
        triggerReceiver(player.getMainHandItem(), GunAnimations.BOLT);
    }

    private static void triggerReceiver(ItemStack gun, RawAnimation anim) {
        if (!(gun.getItem() instanceof GeoGunItem item)) return;
        triggerOn(item, item.getAnimatableInstanceCache().getManagerForId(GeoItem.getId(gun)), anim);
    }

    private static void triggerModule(net.minecraft.resources.ResourceLocation moduleId, RawAnimation anim) {
        ModuleAnimatable module = ModuleAnimatable.of(moduleId);
        triggerOn(module, module.getAnimatableInstanceCache().getManagerForId(0), anim);
    }

    private static void triggerOn(GeoAnimatable animatable, AnimatableManager<? extends GeoAnimatable> manager, RawAnimation anim) {
        if (manager == null) return;
        AnimationController<?> controller = manager.getAnimationControllers().get(CONTROLLER);
        if (controller == null) return;
        controller.forceAnimationReset();
        controller.setAnimation(anim);
    }
}