package dev.ignis.createpneumatictacticals.ammo;

import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import net.minecraftforge.fml.common.Mod;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;
import dev.ignis.createpneumatictacticals.module.GunType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Loads ammo extension JSON from data/<ns>/cpt_ammo/*.json.
 * Key: potato projectile type id (e.g. "create:potato").
 */
@Mod.EventBusSubscriber
public final class AmmoExtensionLoader extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    /**
     * Reads Create's own potato_cannon_projectile_types datapack folder (the
     * EnhancedPotatoCannon approach): extension fields are optional extra keys
     * on those files, keyed by the type id itself. Our own new ammo types are
     * plain new files in the same folder under our namespace — Create's
     * datapack registry picks them up too.
     */
    public static final String FOLDER = "create/potato_projectile/type";
    public AmmoExtensionLoader() {
        super(new com.google.gson.Gson(), FOLDER);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resourceManager, ProfilerFiller profiler) {
        AmmoExtension.clear();
        for (Map.Entry<ResourceLocation, JsonElement> entry : files.entrySet()) {
            ResourceLocation id = entry.getKey();
            try {
                AmmoExtension.put(id.toString(), parse(id, entry.getValue().getAsJsonObject()));
            } catch (Exception ex) {
                LOGGER.error("Failed to parse ammo extension {}: {}", id, ex.getMessage());
            }
        }
        LOGGER.info("Loaded {} ammo extensions", files.size());
    }

    static AmmoExtension parse(ResourceLocation id, JsonObject json) {
        AmmoExtension ext = new AmmoExtension();
        ext.affectRadius = optDouble(json, "affect_radius", ext.affectRadius);
        ext.explosionKnockback = optDouble(json, "explosion_knockback", ext.explosionKnockback);
        ext.explosionDamage = optDouble(json, "explosion_damage", ext.explosionDamage);
        ext.penetrateRatio = optDouble(json, "penetrate_ratio", ext.penetrateRatio);
        if (json.has("max_reflect") || json.has("speed_decay")) {
            ext.maxReflect = (int) optDouble(json, "max_reflect", 10);
            ext.speedDecay = optDouble(json, "speed_decay", 0.5);
        }
        ext.effectiveRange = optDouble(json, "effective_range", ext.effectiveRange);
        ext.damageFalloffRate = optDouble(json, "damage_falloff_rate", ext.damageFalloffRate);
        ext.damage = optDouble(json, "damage", ext.damage);
        ext.spread = optDouble(json, "spread", ext.spread);
        ext.headshotMultiplier = optDouble(json, "headshot_multiplier", ext.headshotMultiplier);
        if (json.has("gun_type")) {
            ext.gunType = GunType.byName(json.get("gun_type").getAsString(), ext.gunType);
        } else {
            // Create's own type jsons carry no gun_type; without this the
            // loaded entry (default LIGHT) shadows the built-in caliber map
            // in AmmoExtension.get and medium/heavy receivers match nothing
            GunType builtin = AmmoExtension.builtinCaliber(id.toString());
            if (builtin != null) ext.gunType = builtin;
        }
        if (json.has("effects")) {
            JsonObject eff = json.getAsJsonObject("effects");
            ext.effects = new AmmoExtension.EffectSpec(
                    parseEffects(eff, "direct"),
                    parseEffects(eff, "explosion"));
        }
        return ext;
    }

    private static List<AmmoExtension.EffectEntry> parseEffects(JsonObject eff, String key) {
        List<AmmoExtension.EffectEntry> out = new ArrayList<>();
        if (eff.has(key)) {
            for (JsonElement el : eff.getAsJsonArray(key)) {
                JsonObject o = el.getAsJsonObject();
                out.add(new AmmoExtension.EffectEntry(
                        o.get("effect").getAsString(),
                        GsonHelper.getAsInt(o, "duration", 1),
                        GsonHelper.getAsInt(o, "amplifier", 0)));
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static double optDouble(JsonObject json, String key, double fallback) {
        if (json.has(key)) {
            try {
                return json.get(key).getAsDouble();
            } catch (NumberFormatException | ClassCastException e) {
                throw new JsonParseException("Field '" + key + "' must be numeric: " + json.get(key));
            }
        }
        return fallback;
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new AmmoExtensionLoader());
    }
}