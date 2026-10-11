package dev.ignis.createpneumatictacticals.client.render;

import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;
import software.bernie.geckolib.util.RenderUtils;

/**
 * Animatable identity for the weapon-module machine tool. There is exactly one
 * machine model, so one singleton covers every placed machine and the item icon.
 *
 * <p>No controllers: the machine has no .animation.json of its own — its moving
 * parts (sliding door, drill base, clamp) are written onto the shared baked
 * model's bones per frame by {@link WeaponModuleMachineRenderer}, which is why
 * every draw goes through {@code reRender} (no animation handling, no bone
 * reset). The tick source only has to be stable for the instance cache.
 */
public final class MachineAnimatable implements GeoAnimatable {

    public static final MachineAnimatable INSTANCE = new MachineAnimatable();

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    private MachineAnimatable() {
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar registrar) {
        // no controller: bones are driven procedurally, not by keyframes
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    @Override
    public double getTick(Object instance) {
        return RenderUtils.getCurrentTick();
    }
}
