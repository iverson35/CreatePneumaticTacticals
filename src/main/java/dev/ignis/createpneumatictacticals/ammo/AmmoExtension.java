package dev.ignis.createpneumatictacticals.ammo;

import com.simibubi.create.api.equipment.potatoCannon.PotatoCannonProjectileType;
import dev.ignis.createpneumatictacticals.module.GunType;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.item.Item;

/**
 * Extended ammo attributes for Create potato cannon projectile types.
 * Attached by string id ("create:potato" etc.) and loaded from datapack JSON;
 * missing entries fall back to DEFAULTS.
 */
public final class AmmoExtension {

    /** Shared fallback used when no extension data is present for an ammo id. */
    public static final AmmoExtension DEFAULTS = new AmmoExtension();

    // --- explosion ---
    public double affectRadius = 0;          // blocks; 0 = no explosion
    public double explosionKnockback = 0;    // can be negative (pull)
    public double explosionDamage = 0;
    public double penetrateRatio = 0;        // 0..1, cover penetration for explosion/effect LOS

    // --- bounce ---
    public int maxReflect = 0;               // >0 (or speedDecay set) enables reflection
    public double speedDecay = 0.5;

    // --- range / falloff ---
    public double effectiveRange = 256;
    public double damageFalloffRate = 0;     // damage loss per block beyond effective range

    // --- gunplay ---
    // fire rate deliberately has NO extension field: it is always the Create
    // type's reload_ticks (potato-cannon parity), scaled by the gun's
    // fire_rate_multiplier
    public double damage = -1;              // <=0: use Create's type.damage()
    public double spread = 1.0;              // degrees; S0 of the hipfire accuracy model
    public double headshotMultiplier = 1.5;
    public GunType gunType = GunType.LIGHT;  // HEAVY receivers accept lighter ammo

    // --- effects ---
    public record EffectEntry(String effectId, int durationSeconds, int amplifier) {
        public static final String FIRE = "minecraft:fire";
    }

    public record EffectSpec(java.util.List<EffectEntry> direct, java.util.List<EffectEntry> explosion) {
        public static final EffectSpec EMPTY = new EffectSpec(java.util.List.of(), java.util.List.of());
    }

    public EffectSpec effects = EffectSpec.EMPTY;

    /** live extension table; keyed by potato projectile type id */
    private static final Map<String, AmmoExtension> TABLE = new HashMap<>();

    public static AmmoExtension get(String ammoId) {
        AmmoExtension ext = TABLE.get(ammoId);
        if (ext != null) return ext;
        // no datapack entry: fall back to the built-in preset when we define one
        BuiltinAmmo.Builder preset = BuiltinAmmo.preset(ammoId);
        if (preset != null) {
            AmmoExtension def = new AmmoExtension();
            preset.applyTo(def);
            return def;
        }
        return DEFAULTS;
    }

    public static void put(String ammoId, AmmoExtension ext) {
        TABLE.put(ammoId, ext);
    }

    /**
     * Ticks between two shots of this ammo out of this gun: the Create type's
     * own cadence scaled by the gun's fire_rate_multiplier, floored at 1 tick
     * (a multiplier can make the interval fractional). The single source for
     * the server's rate limit, the client's local gate and the tooltip's RPM.
     */
    public static long fireIntervalTicks(PotatoCannonProjectileType type, double fireRateMultiplier) {
        return Math.max(1, (long) (type.reloadTicks() / fireRateMultiplier));
    }

    /**
     * Point-blank shot damage before the gun's damage_multiplier: this ammo's
     * own damage when it defines one, else Create's type value. Range falloff
     * and headshots apply on top of it at hit time.
     */
    public static double baseDamage(PotatoCannonProjectileType type, AmmoExtension ext) {
        return ext.damage > 0 ? ext.damage : type.damage();
    }

    public static void clear() {
        TABLE.clear();
    }

    /** representative content item for a potato projectile type id; null when unknown */
    public static Item contentItemFor(net.minecraft.core.RegistryAccess registries, String ammoId) {
        if (ammoId == null) return null;
        var type = registries.registryOrThrow(com.simibubi.create.api.registry.CreateRegistries.POTATO_PROJECTILE_TYPE)
                .get(net.minecraft.resources.ResourceLocation.tryParse(ammoId));
        if (type == null) return null;
        return type.items().stream().findFirst().map(h -> (Item) h.value()).orElse(null);
    }
}