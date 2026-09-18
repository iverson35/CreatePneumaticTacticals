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
}