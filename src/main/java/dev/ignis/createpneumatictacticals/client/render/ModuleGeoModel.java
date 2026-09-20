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
    /** diag: one log per (module, colors) pair */
    private static final java.util.Set<String> DIAG_SEEN = java.util.concurrent.ConcurrentHashMap.newKeySet();
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
        // one-shot diagnostic per module id — this runs per frame, a bare
        // log would flood. Remove once dyeing is confirmed end to end.
        if (currentStack.hasTag()) {
            String modId = String.valueOf(ModuleItem.getModuleId(currentStack));
            String seenKey = modId + ":" + java.util.Arrays.toString(colors);
            if (DIAG_SEEN.add(seenKey)) {
                com.mojang.logging.LogUtils.getLogger().info(
                        "geoModel texture: module={} colors={} tag={}", modId,
                        java.util.Arrays.toString(colors), currentStack.getTag());
            }
        }
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