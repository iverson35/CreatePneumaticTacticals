package dev.ignis.createpneumatictacticals.ammo;

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

    /**
     * Built-in caliber classification for every Create potato projectile
     * type (design constants — the four-caliber system). Datapack entries
     * override these, so gunpack authors can reclassify. Rules:
     * <ul>
     *   <li>SHOTGUN (highest priority): type splits into multiple pellets
     *       (sweet_berry 3, glow_berry 2, chocolate_berry 3)</li>
     *   <li>HEAVY: low fire rate / high damage (melon_block, blaze_cake,
     *       cake, golden_carrot)</li>
     *   <li>LIGHT: high fire rate / low damage (beetroot, melon_slice,
     *       glistering_melon, carrot)</li>
     *   <li>MEDIUM: everything else (balanced)</li>
     * </ul>
     * In-code (not datapack) because Create's jar ships its own JSONs for
     * the same resource folder — resource-pack order would silently drop
     * our gun_type overrides. Gun packs may still override via JSON.
     */
    private static final Map<String, GunType> BUILTIN_CALIBERS = Map.ofEntries(
            Map.entry("create:sweet_berry", GunType.SHOTGUN),
            Map.entry("create:glow_berry", GunType.SHOTGUN),
            Map.entry("create:chocolate_berry", GunType.SHOTGUN),
            Map.entry("create:melon_block", GunType.HEAVY),
            Map.entry("create:blaze_cake", GunType.HEAVY),
            Map.entry("create:cake", GunType.HEAVY),
            Map.entry("create:golden_carrot", GunType.HEAVY),
            Map.entry("create:beetroot", GunType.LIGHT),
            Map.entry("create:melon_slice", GunType.LIGHT),
            Map.entry("create:glistering_melon", GunType.LIGHT),
            Map.entry("create:carrot", GunType.LIGHT),
            Map.entry("create:potato", GunType.MEDIUM),
            Map.entry("create:baked_potato", GunType.MEDIUM),
            Map.entry("create:poison_potato", GunType.MEDIUM),
            Map.entry("create:chorus_fruit", GunType.MEDIUM),
            Map.entry("create:apple", GunType.MEDIUM),
            Map.entry("create:honeyed_apple", GunType.MEDIUM),
            Map.entry("create:golden_apple", GunType.MEDIUM),
            Map.entry("create:enchanted_golden_apple", GunType.MEDIUM),
            Map.entry("create:pumpkin_block", GunType.MEDIUM),
            Map.entry("create:pumpkin_pie", GunType.MEDIUM),
            Map.entry("create:fish", GunType.MEDIUM),
            Map.entry("create:pufferfish", GunType.MEDIUM),
            Map.entry("create:suspicious_stew", GunType.MEDIUM));

    /**
     * Built-in bounce tuning for specific ammo (design constants). Same
     * reasoning as BUILTIN_CALIBERS: Create's jar ships the JSONs for the
     * shared resource folder, so a mod-side resource override loses the pack
     * order battle (verified in-game: the honeyed apple parsed with no bounce
     * when the values only lived in our own JSON). Datapack entries still win:
     * a JSON carrying max_reflect/speed_decay overrides this table, including
     * an explicit {@code "max_reflect": 0} to switch bouncing off.
     */
    private static final Map<String, Bounce> BUILTIN_BOUNCE = Map.of(
            "create:honeyed_apple", new Bounce(4, 0.72));

    /** max_reflect / speed_decay pair: bounces left and speed kept per bounce */
    public record Bounce(int maxReflect, double speedDecay) {
    }

    public static AmmoExtension get(String ammoId) {
        AmmoExtension ext = TABLE.get(ammoId);
        if (ext != null) return ext;
        // no datapack entry: use the built-in caliber/bounce when classified
        GunType caliber = BUILTIN_CALIBERS.get(ammoId);
        Bounce bounce = BUILTIN_BOUNCE.get(ammoId);
        if (caliber != null || bounce != null) {
            AmmoExtension def = new AmmoExtension();
            if (caliber != null) def.gunType = caliber;
            if (bounce != null) {
                def.maxReflect = bounce.maxReflect();
                def.speedDecay = bounce.speedDecay();
            }
            return def;
        }
        return DEFAULTS;
    }

    /** Built-in caliber for an ammo id, or null when unclassified. */
    static GunType builtinCaliber(String ammoId) {
        return BUILTIN_CALIBERS.get(ammoId);
    }

    /** Built-in bounce tuning for an ammo id, or null when it does not bounce. */
    static Bounce builtinBounce(String ammoId) {
        return BUILTIN_BOUNCE.get(ammoId);
    }
    public static void put(String ammoId, AmmoExtension ext) {
        TABLE.put(ammoId, ext);
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