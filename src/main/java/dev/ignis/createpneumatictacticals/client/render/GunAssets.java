package dev.ignis.createpneumatictacticals.client.render;

import dev.ignis.createpneumatictacticals.gun.GunNbt;
import dev.ignis.createpneumatictacticals.module.ModuleDefinition;
import dev.ignis.createpneumatictacticals.module.ModuleType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.Map;

/**
 * Per-stack resource resolution for the assembled gun. The receiver defines
 * the base model/texture/animation (plan_v2 模型组织); module models attach at
 * locator bones. Overridden modules (Armourer's Workshop skins, future) are
 * detected via ModuleRenderOverrides.
 */
public final class GunAssets {

    public record Assets(ResourceLocation model, ResourceLocation texture, ResourceLocation animation) {}

    private static final ResourceLocation PLACEHOLDER_MODEL =
            new ResourceLocation("createpneumatictacticals", "geo/gun/placeholder.geo.json");
    private static final ResourceLocation PLACEHOLDER_TEXTURE =
            new ResourceLocation("createpneumatictacticals", "textures/gun/placeholder.png");
    private static final ResourceLocation PLACEHOLDER_ANIM =
            new ResourceLocation("createpneumatictacticals", "animations/gun/placeholder.animation.json");

    private GunAssets() {}

    public static Assets forStack(ItemStack stack) {
        Map<ModuleType, ModuleDefinition> modules = GunNbt.readModules(stack);
        ModuleDefinition receiver = modules.get(ModuleType.RECEIVER);
        if (receiver == null) return new Assets(PLACEHOLDER_MODEL, PLACEHOLDER_TEXTURE, PLACEHOLDER_ANIM);
        String ns = receiver.id.getNamespace();
        String path = receiver.id.getPath();
        return new Assets(
                new ResourceLocation(ns, "geo/gun/" + path + ".geo.json"),
                new ResourceLocation(ns, "textures/gun/" + path + ".png"),
                new ResourceLocation(ns, "animations/gun/" + path + ".animation.json"));
    }

    public static boolean isModuleOverridden(ItemStack gunStack, ModuleDefinition def) {
        return ModuleRenderOverrides.isOverridden(gunStack, def.id);
    }
}