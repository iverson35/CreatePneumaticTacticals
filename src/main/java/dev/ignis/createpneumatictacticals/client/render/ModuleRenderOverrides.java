package dev.ignis.createpneumatictacticals.client.render;

import dev.ignis.createpneumatictacticals.gun.GunNbt;
import dev.ignis.createpneumatictacticals.module.ModuleDefinition;
import dev.ignis.createpneumatictacticals.module.ModuleType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Registry of module render overrides. The AW integration will register an
 * implementation here when the mod is present; the default gun renderer
 * consults it before drawing each module model.
 */
public final class ModuleRenderOverrides {

    private static final List<ModuleRenderOverride> OVERRIDES = new ArrayList<>();

    private ModuleRenderOverrides() {}

    public static void register(ModuleRenderOverride override) {
        OVERRIDES.add(override);
    }

    @Nullable
    public static ModuleRenderOverride find(ItemStack gunStack, ResourceLocation moduleId) {
        for (ModuleRenderOverride o : OVERRIDES) {
            if (o.claimsRender(gunStack, moduleId)) return o;
        }
        return null;
    }

    /** whether the default model should be skipped for this module */
    public static boolean isOverridden(ItemStack gunStack, ResourceLocation moduleId) {
        return find(gunStack, moduleId) != null;
    }

    public static boolean anyModuleOverridden(ItemStack gunStack) {
        Map<ModuleType, ModuleDefinition> modules = GunNbt.readModules(gunStack);
        for (ModuleDefinition def : modules.values()) {
            if (isOverridden(gunStack, def.id)) return true;
        }
        return false;
    }
}