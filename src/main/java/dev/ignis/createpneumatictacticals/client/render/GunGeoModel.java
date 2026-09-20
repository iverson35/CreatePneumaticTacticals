package dev.ignis.createpneumatictacticals.client.render;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import software.bernie.geckolib.model.GeoModel;

/**
 * Per-stack GeckoLib model: pulls receiver resources from gun NBT each frame.
 * T is GeoGunItem itself (Item & GeoAnimatable).
 */
public final class GunGeoModel extends GeoModel<dev.ignis.createpneumatictacticals.item.GeoGunItem> {

    private ItemStack currentStack;
    private GunAssets.Assets currentAssets;

    public void setStack(ItemStack stack) {
        if (stack == currentStack) return;
        this.currentStack = stack;
        this.currentAssets = stack == null ? null : GunAssets.forStack(stack);
    }

    /**
     * Runs an action with the animation/model context pinned to a specific
     * stack. Animation name resolution (RawAnimation -> baked Animation)
     * happens through getAnimationResource, which depends on the currently
     * rendered stack; triggers fired between frames must pin the context or
     * they resolve against whatever was rendered last (e.g. the placeholder).
     */
    public void withStack(ItemStack stack, Runnable action) {
        ItemStack prev = currentStack;
        setStack(stack);
        try {
            action.run();
        } finally {
            setStack(prev);
        }
    }

    /** the stack currently being rendered (set per pass by the renderer) */
    @org.jetbrains.annotations.Nullable
    public ItemStack currentStack() {
        return currentStack;
    }

    @Override
    public ResourceLocation getModelResource(dev.ignis.createpneumatictacticals.item.GeoGunItem animatable) {
        return currentAssets != null ? currentAssets.model()
                : GunAssets.forStack(ItemStack.EMPTY).model();
    }

    @Override
    public ResourceLocation getTextureResource(dev.ignis.createpneumatictacticals.item.GeoGunItem animatable) {
        return currentAssets != null ? currentAssets.texture()
                : GunAssets.forStack(ItemStack.EMPTY).texture();
    }

    @Override
    public ResourceLocation getAnimationResource(dev.ignis.createpneumatictacticals.item.GeoGunItem animatable) {
        return currentAssets != null ? currentAssets.animation()
                : GunAssets.forStack(ItemStack.EMPTY).animation();
    }

    /**
     * Animations only play while the gun is in a hand (first/third person).
     * Inventory icons, dropped guns and item frames skip the animation pass
     * and reset to the rest pose, so a reload interrupted mid-way never
     * freezes the icon with the bolt pulled back.
     */
    @Override
    public void handleAnimations(dev.ignis.createpneumatictacticals.item.GeoGunItem animatable,
                                 long instanceId, software.bernie.geckolib.core.animation.AnimationState<dev.ignis.createpneumatictacticals.item.GeoGunItem> state) {
        Object perspective = state.getData(software.bernie.geckolib.constant.DataTickets.ITEM_RENDER_PERSPECTIVE);
        boolean handheld = perspective == net.minecraft.world.item.ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                || perspective == net.minecraft.world.item.ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                || perspective == net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_LEFT_HAND
                || perspective == net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
        if (!handheld) {
            GunAnimations.resetToRestPose(this);
            return;
        }
        super.handleAnimations(animatable, instanceId, state);
        // First-person pass is the pose the local player actually sees;
        // capture it so the next triggered animation transitions from
        // truth instead of GeckoLib's stale snapshot (see GunAnimations.captureLivePose)
        if (perspective == net.minecraft.world.item.ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                || perspective == net.minecraft.world.item.ItemDisplayContext.FIRST_PERSON_RIGHT_HAND) {
            GunAnimations.captureLivePose("recv", this, instanceId);
        }
    }
}
