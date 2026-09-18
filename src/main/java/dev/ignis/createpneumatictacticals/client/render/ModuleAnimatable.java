package dev.ignis.createpneumatictacticals.client.render;

import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.HashMap;
import java.util.Map;

/**
 * Singleton animatable per module definition. Module models may ship their own
 * animation json (assets/&lt;ns&gt;/animations/gun/&lt;moduleid&gt;.animation.json) with
 * the same animation names as the receiver (reload / reload_round / bolt) —
 * the receiver animates its own bones (charge handle), the feed module animates
 * its own bones (magazine / per-round loading). GunAnimationDriver triggers
 * both at once.
 */
public final class ModuleAnimatable implements GeoAnimatable {

    private static final Map<ResourceLocation, ModuleAnimatable> INSTANCES = new HashMap<>();

    private final ResourceLocation moduleId;
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    private ModuleAnimatable(ResourceLocation moduleId) {
        this.moduleId = moduleId;
    }

    public static ModuleAnimatable of(ResourceLocation moduleId) {
        return INSTANCES.computeIfAbsent(moduleId, ModuleAnimatable::new);
    }

    public ResourceLocation moduleId() {
        return moduleId;
    }

    /** animation json path convention for modules (same folder as receivers) */
    public static ResourceLocation animationId(ResourceLocation moduleId) {
        return new ResourceLocation(moduleId.getNamespace(),
                "animations/gun/" + moduleId.getPath() + ".animation.json");
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar registrar) {
        // one-shot anim controller, mirrored from the gun item ("anim")
        registrar.add(new AnimationController<>(this, "anim", 2, state -> PlayState.STOP));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    @Override
    public double getTick(Object o) {
        var level = software.bernie.geckolib.util.ClientUtils.getLevel();
        return level != null ? level.getGameTime() : 0;
    }
}