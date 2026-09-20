package dev.ignis.createpneumatictacticals.module;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import dev.ignis.createpneumatictacticals.gunpack.GunPacks;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * Central registry of module definitions, loaded from installed gunpacks
 * ({@code <gamedir>/gunpacks/<pack>/modules/*.json}) on BOTH sides — the
 * network handshake guarantees client and server agree on the contents.
 * Reload: {@code /cpt reload} (server) or F3+T (client).
 */
public final class ModuleManager {

    /** the module definition a stack carries, or null (non-module / no id) */
    public static ModuleDefinition definitionOf(net.minecraft.world.item.ItemStack stack) {
        if (stack.getItem() instanceof dev.ignis.createpneumatictacticals.item.ModuleItem module) {
            net.minecraft.resources.ResourceLocation id = module.getModuleId(stack);
            return id == null ? null : get(id);
        }
        return null;
    }

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile Map<ResourceLocation, ModuleDefinition> MODULES = Map.of();

    private ModuleManager() {}

    public static ModuleDefinition get(ResourceLocation id) {
        return MODULES.get(id);
    }

    public static Map<ResourceLocation, ModuleDefinition> all() {
        return MODULES;
    }

    /** (re)load every module definition from the installed gunpacks */
    public static synchronized void loadFromGunPacks() {
        Map<ResourceLocation, ModuleDefinition> fresh = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonObject> entry : GunPacks.loadModuleJsons().entrySet()) {
            ResourceLocation id = entry.getKey();
            try {
                fresh.put(id, ModuleDefinition.fromJson(id, entry.getValue()));
            } catch (Exception ex) {
                LOGGER.error("Failed to load module definition {}: {}", id, ex.getMessage());
            }
        }
        MODULES = Map.copyOf(fresh);
        LOGGER.info("Loaded {} module definitions from gunpacks", MODULES.size());
    }
}
