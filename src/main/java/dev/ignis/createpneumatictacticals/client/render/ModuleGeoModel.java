package dev.ignis.createpneumatictacticals.client.render;

import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import dev.ignis.createpneumatictacticals.item.ModuleItem;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import software.bernie.geckolib.model.GeoModel;

/**
 * Per-stack module item model: resolves geo/texture from the stack's ModuleId
 * NBT (same resource convention as receivers: geo/gun/<id>.geo.json +
 * textures/gun/<id>.png). Falls back to the shared placeholder while a module
 * has no art assets.
 */
public final class ModuleGeoModel extends GeoModel<ModuleItem> {

    private static final ResourceLocation PLACEHOLDER_MODEL =
            new ResourceLocation(CreatePneumaticTacticals.MODID, "geo/gun/placeholder.geo.json");
    private static final ResourceLocation PLACEHOLDER_TEXTURE =
            new ResourceLocation(CreatePneumaticTacticals.MODID, "textures/gun/placeholder.png");
    private static final ResourceLocation PLACEHOLDER_ANIM =
            new ResourceLocation(CreatePneumaticTacticals.MODID, "animations/gun/placeholder.animation.json");

    private ItemStack currentStack = ItemStack.EMPTY;

    public void setStack(ItemStack stack) {
        this.currentStack = stack == null ? ItemStack.EMPTY : stack;
    }

    @Override
    public ResourceLocation getModelResource(ModuleItem animatable) {
        return resolve("geo/gun/%s.geo.json", PLACEHOLDER_MODEL);
    }

    @Override
    public ResourceLocation getTextureResource(ModuleItem animatable) {
        ResourceLocation base = resolve("textures/gun/%s.png", PLACEHOLDER_TEXTURE);
        // dye regions live on the module item's own NBT (set by the
        // workbench dyeing) — same bake as the on-gun path (DyedTextures)
        int[] colors = ModuleItem.getDyeColors(currentStack);
        return colors != null ? DyedTextures.resolve(base, colors) : base;
    }

    @Override
    public ResourceLocation getAnimationResource(ModuleItem animatable) {
        return PLACEHOLDER_ANIM;
    }

    private ResourceLocation resolve(String pattern, ResourceLocation fallback) {
        ResourceLocation id = ModuleItem.getModuleId(currentStack);
        if (id == null) return fallback;
        ResourceLocation loc = new ResourceLocation(id.getNamespace(), pattern.formatted(id.getPath()));
        return Minecraft.getInstance().getResourceManager().getResource(loc).isPresent() ? loc : fallback;
    }
}