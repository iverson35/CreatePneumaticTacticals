package dev.ignis.createpneumatictacticals.ammo;

import dev.ignis.createpneumatictacticals.module.GunType;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Built-in presets for Create's own potato projectile types: one fluent chain
 * per ammo, keyed by full ammo id.
 *
 * <p>This is the hand-balancing surface. Retune an ammo by editing its chain
 * ({@code .range(..)}, {@code .damage(..)}, {@code .spread(..)}, ...), or add a
 * new {@code ammo("create:..")} chain for a type Create ships that we have not
 * touched yet. Only Create's roster belongs here — ammo added by another
 * datapack is not our business and is not listed.
 *
 * <p>Precedence: the loader applies the preset first and then reads the
 * datapack JSON over it field by field, so a pack entry for the same id still
 * overrides any single value. An unlisted id keeps the plain
 * {@link AmmoExtension} defaults (no bounce, no falloff).
 *
 * <p>In-code rather than datapack because Create's jar ships its own JSONs for
 * the same resource folder — a mod-side resource override would silently lose
 * the pack-order battle (verified in-game: the honeyed apple parsed with no
 * bounce when the values only lived in our own JSON).
 *
 * <p>Caliber rules (the four-caliber system):
 * <ul>
 *   <li>SHOTGUN (highest priority): type splits into multiple pellets
 *       (sweet_berry 3, glow_berry 2, chocolate_berry 3)</li>
 *   <li>HEAVY: low fire rate / high damage (melon_block, blaze_cake,
 *       cake, golden_carrot)</li>
 *   <li>LIGHT: high fire rate / low damage (beetroot, melon_slice,
 *       glistering_melon, carrot)</li>
 *   <li>MEDIUM: everything else (balanced)</li>
 * </ul>
 *
 * <p>Damage is ours, not Create's type damage: short range is paid back in
 * punch. The 48-block tier keeps the type's damage, and the closer an ammo's
 * range sits to 16 blocks the higher it climbs, up to x1.5 at 16. Every value
 * below is that rule applied once
 * ({@code type_damage * (1 + 0.5 * (48 - range) / 32)}) and then frozen:
 * nothing recomputes it, so hand-tune freely. Damage is the one field where
 * this table beats a type JSON (Create ships its own damage for most types,
 * so the loader skips that key for any ammo listed here) - everything else
 * still lets the JSON win. The two buff rounds (golden apple cures zombie
 * villagers, enchanted golden apple applies food effects) carry no damage
 * override at all: their payload is the effect.
 *
 * <p>Range is the falloff start distance in blocks: beyond it damage drops by
 * {@code damage_falloff_rate} per block, or by {@code base / range} when the
 * ammo authors no rate, so a shot is spent by twice its range. The numbers
 * started as 32 + (damage - 2) * 64 / 13 and were halved to 16..48.
 */
public final class BuiltinAmmo {

    private BuiltinAmmo() {}

    private static final Map<String, Builder> TABLE = new LinkedHashMap<>();

    static {
        // weakest first: caliber + falloff start (blocks) + damage (ours)
        ammo("create:beetroot").caliber(GunType.LIGHT).range(16.0).damage(3.0);
        ammo("create:glow_berry").caliber(GunType.SHOTGUN).range(16.0).damage(3.0);
        ammo("create:chorus_fruit").caliber(GunType.MEDIUM).range(18.5).damage(4.4);
        ammo("create:melon_slice").caliber(GunType.LIGHT).range(18.5).damage(4.4);
        ammo("create:suspicious_stew").caliber(GunType.MEDIUM).range(18.5).damage(4.4);
        ammo("create:sweet_berry").caliber(GunType.SHOTGUN).range(18.5).damage(4.4);
        ammo("create:carrot").caliber(GunType.LIGHT).range(20.9).damage(5.7);
        ammo("create:chocolate_berry").caliber(GunType.SHOTGUN).range(20.9).damage(5.7);
        ammo("create:fish").caliber(GunType.MEDIUM).range(20.9).damage(5.7);
        ammo("create:pufferfish").caliber(GunType.MEDIUM).range(20.9).damage(5.7);
        ammo("create:apple").caliber(GunType.MEDIUM).range(23.4).damage(6.9);
        ammo("create:baked_potato").caliber(GunType.MEDIUM).range(23.4).damage(6.9);
        ammo("create:glistering_melon").caliber(GunType.LIGHT).range(23.4).damage(6.9);
        ammo("create:poison_potato").caliber(GunType.MEDIUM).range(23.4).damage(6.9);
        ammo("create:potato").caliber(GunType.MEDIUM).range(23.4).damage(6.9);
        ammo("create:honeyed_apple").caliber(GunType.MEDIUM).bounce(4, 0.72).range(25.8).damage(8.1);
        ammo("create:pumpkin_block").caliber(GunType.MEDIUM).range(25.8).damage(8.1);
        ammo("create:pumpkin_pie").caliber(GunType.MEDIUM).range(28.3).damage(9.2);
        ammo("create:cake").caliber(GunType.HEAVY).range(30.8).damage(10.2);
        ammo("create:melon_block").caliber(GunType.HEAVY).range(30.8).damage(10.2);
        ammo("create:golden_carrot").caliber(GunType.HEAVY).range(40.6).damage(13.4);
        ammo("create:blaze_cake").caliber(GunType.HEAVY).range(48.0).damage(15.0);

        // buff rounds: no damage override - the payload is the effect
        ammo("create:golden_apple").caliber(GunType.MEDIUM).range(16.0);
        ammo("create:enchanted_golden_apple").caliber(GunType.MEDIUM).range(16.0);
    }

    private static Builder ammo(String id) {
        Builder b = new Builder();
        TABLE.put(id, b);
        return b;
    }

    /** preset for an ammo id, or null when the id is not one of ours */
    @Nullable
    public static Builder preset(String ammoId) {
        return TABLE.get(ammoId);
    }

    /**
     * Fluent per-ammo preset. Every setter returns {@code this} so one ammo is
     * one chain; a field left unset keeps the {@link AmmoExtension} default,
     * and a datapack value for the same field still wins at load time.
     */
    public static final class Builder {
        @Nullable private GunType caliber;
        @Nullable private Integer maxReflect;
        @Nullable private Double speedDecay;
        @Nullable private Double range;
        @Nullable private Double falloffRate;
        @Nullable private Double damage;
        @Nullable private Double spread;
        @Nullable private Double headshot;
        @Nullable private Double affectRadius;
        @Nullable private Double explosionKnockback;
        @Nullable private Double explosionDamage;
        @Nullable private Double penetrateRatio;

        /** receiver caliber this ammo can be fired from */
        public Builder caliber(GunType v) {
            this.caliber = v;
            return this;
        }

        /** bounces left on impact and the speed kept per bounce */
        public Builder bounce(int maxReflect, double speedDecay) {
            this.maxReflect = maxReflect;
            this.speedDecay = speedDecay;
            return this;
        }

        /** falloff start distance in blocks */
        public Builder range(double v) {
            this.range = v;
            return this;
        }

        /** damage loss per block past the range; 0 = derive base / range */
        public Builder falloffRate(double v) {
            this.falloffRate = v;
            return this;
        }

        /** damage per hit; <= 0 = keep Create's type damage */
        public Builder damage(double v) {
            this.damage = v;
            return this;
        }

        /** hipfire cone in degrees (S0 of the accuracy model) */
        public Builder spread(double v) {
            this.spread = v;
            return this;
        }

        /** headshot damage multiplier */
        public Builder headshot(double v) {
            this.headshot = v;
            return this;
        }

        /** explosion radius, knockback and damage */
        public Builder explosion(double radius, double knockback, double damage) {
            this.affectRadius = radius;
            this.explosionKnockback = knockback;
            this.explosionDamage = damage;
            return this;
        }

        /** cover penetration for explosion / effect line of sight, 0..1 */
        public Builder penetrate(double v) {
            this.penetrateRatio = v;
            return this;
        }

        /** true when this preset carries a damage override */
        boolean hasDamage() {
            return damage != null;
        }

        /** writes the preset onto a fresh extension; unset fields are untouched */
        public void applyTo(AmmoExtension ext) {
            if (caliber != null) ext.gunType = caliber;
            if (maxReflect != null) ext.maxReflect = maxReflect;
            if (speedDecay != null) ext.speedDecay = speedDecay;
            if (range != null) ext.effectiveRange = range;
            if (falloffRate != null) ext.damageFalloffRate = falloffRate;
            if (damage != null) ext.damage = damage;
            if (spread != null) ext.spread = spread;
            if (headshot != null) ext.headshotMultiplier = headshot;
            if (affectRadius != null) ext.affectRadius = affectRadius;
            if (explosionKnockback != null) ext.explosionKnockback = explosionKnockback;
            if (explosionDamage != null) ext.explosionDamage = explosionDamage;
            if (penetrateRatio != null) ext.penetrateRatio = penetrateRatio;
        }
    }
}
