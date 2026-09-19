package dev.ignis.createpneumatictacticals.ammo;

import dev.ignis.createpneumatictacticals.module.GunType;
import java.util.HashMap;
import java.util.Map;
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
    public double damage = -1;              // <=0: use Create's type.damage()
    public double fireRate = 300;            // rpm; final rate = fireRate * fire_rate_multiplier
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
        return TABLE.getOrDefault(ammoId, DEFAULTS);
    }

    public static void put(String ammoId, AmmoExtension ext) {
        TABLE.put(ammoId, ext);
    }

    public static void clear() {
        TABLE.clear();
    }

    /** linear damage decay beyond effective range; <=0 means full damage to self-destruct distance */
    public double damageAt(double distance, double baseDamage) {
        double range = effectiveRange;
        if (distance <= range) return baseDamage;
        double lost = (distance - range) * damageFalloffRate;
        if (damageFalloffRate <= 0) return baseDamage;
        return Math.max(0, baseDamage - lost);
    }
}