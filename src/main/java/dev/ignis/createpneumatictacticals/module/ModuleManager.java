package dev.ignis.createpneumatictacticals.module;

import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * Central registry of module definitions, loaded from data/<ns>/cpt_modules/*.json.
 */
@Mod.EventBusSubscriber
public final class ModuleManager extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String FOLDER = "cpt_modules";

    private static final Map<ResourceLocation, ModuleDefinition> MODULES = new HashMap<>();

    public ModuleManager() {
        super(new com.google.gson.Gson(), FOLDER);
    }

    public static ModuleDefinition get(ResourceLocation id) {
        return MODULES.get(id);
    }

    public static Map<ResourceLocation, ModuleDefinition> all() {
        return Map.copyOf(MODULES);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resourceManager, ProfilerFiller profiler) {
        MODULES.clear();
        for (Map.Entry<ResourceLocation, JsonElement> entry : files.entrySet()) {
            ResourceLocation id = entry.getKey();
            try {
                ModuleDefinition def = ModuleDefinition.fromJson(id, entry.getValue().getAsJsonObject());
                MODULES.put(id, def);
            } catch (Exception ex) {
                LOGGER.error("Failed to load module definition {}: {}", id, ex.getMessage());
            }
        }
        LOGGER.info("Loaded {} module definitions", MODULES.size());
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new ModuleManager());
    }
}