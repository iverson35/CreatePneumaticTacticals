package dev.ignis.createpneumatictacticals.module;

import dev.ignis.createpneumatictacticals.gunpack.GunPacks;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Central registry of paint (涂装) definitions, loaded from installed
 * gunpacks ({@code <gamedir>/gunpacks/<pack>/paints/*.json}) on BOTH sides
 * alongside the module definitions — the paint files join the module
 * content hash, so the network handshake covers them too.
 * Reload: {@code /cpt reload} (server) or F3+T (client), same chain as modules.
 */
public final class PaintManager {

    private static volatile Map<ResourceLocation, PaintDefinition> PAINTS = Map.of();

    private PaintManager() {}

    public static PaintDefinition get(ResourceLocation id) {
        return PAINTS.get(id);
    }

    public static Map<ResourceLocation, PaintDefinition> all() {
        return PAINTS;
    }

    /** (re)load every paint definition from the installed gunpacks */
    public static synchronized void loadFromGunPacks() {
        Map<ResourceLocation, PaintDefinition> fresh = new java.util.HashMap<>();
        for (Map.Entry<ResourceLocation, com.google.gson.JsonObject> entry :
                GunPacks.loadPaintJsons().entrySet()) {
            ResourceLocation id = entry.getKey();
            try {
                fresh.put(id, PaintDefinition.fromJson(id, entry.getValue()));
            } catch (Exception ex) {
                com.mojang.logging.LogUtils.getLogger()
                        .error("Failed to load paint definition {}: {}", id, ex.getMessage());
            }
        }
        PAINTS = Map.copyOf(fresh);
        com.mojang.logging.LogUtils.getLogger()
                .info("Loaded {} paint definitions from gunpacks", PAINTS.size());
    }

    /**
     * Resolves the painted texture id for a module: the texture of the
     * paint registered on the item's NBT, when that paint exists AND
     * applies to this module. Texture-existence probing is the caller's
     * job (client code): a missing texture file must fall back to the
     * module's base texture rather than breaking the render — a paint
     * whose art is missing simply renders unpainted.
     */
    public static @Nullable ResourceLocation texture(@Nullable ResourceLocation paintId,
                                                     ResourceLocation moduleId) {
        if (paintId == null) return null;
        PaintDefinition paint = get(paintId);
        if (paint == null || !paint.appliesTo(moduleId)) return null;
        return paint.textureFor(moduleId);
    }
}