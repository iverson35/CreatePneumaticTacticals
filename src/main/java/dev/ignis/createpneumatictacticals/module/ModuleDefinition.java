package dev.ignis.createpneumatictacticals.module;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Data-driven module definition, loaded from data/<ns>/cpt_modules/*.json.
 */
public final class ModuleDefinition {

    public enum AffectedMode {
        EXCLUDE, INCLUDE, KEEP_EMPTY, NOT_EMPTY
    }

    public record Affected(ModuleType type, AffectedMode mode, List<ResourceLocation> value) {
    }

    public final ResourceLocation id;
    public final ModuleType type;
    public final List<Affected> affected;
    /** additive modifiers, may be empty */
    public final double reloadSpeed, damageMultiplier, fireRateMultiplier,
            hipfireAccuracyMultiplier, ergonomics, bulletSpeed,
            recoilMultiplier, recoilRecovery;
    /** muzzle: -10..10 gas suppression; -10 = double smoke, +10 = none */
    public final double gasSuppression;
    /** receiver/barrel: gun type; barrel must match the installed receiver's */
    @Nullable public final GunType gunType;
    @Nullable public final List<FireMode> fireModes;
    @Nullable public final String fireSound;
    /** receiver-only: lang key for the auto-created gun's display name */
    @Nullable public final String gunName;
    public final double baseRecoilPitch, baseRecoilYaw;
    /** feed-only fields */
    @Nullable public final FeedType feedType;
    public final int loadAmount, clipSize;
    /** supply-only fields */
    @Nullable public final SupplyType supplyType;
    public final int airCapacity, airPerShot;
    /** sight-only fields */
    public final double aimZoom, tacticalAimZoom;
    /** appearance */
    @Nullable public final String maskPath;
    @Nullable public final int[] defaultColors;
    /** handguard: exposed attachment points; empty = no attachment slots */
    public final List<HandguardPosition> attachmentPoints;
    /** handguard_attachment: positions this attachment can mount to */
    public final List<HandguardPosition> positions;

    private ModuleDefinition(Builder b) {
        this.id = b.id;
        this.type = b.type;
        this.affected = List.copyOf(b.affected);
        this.reloadSpeed = b.reloadSpeed;
        this.damageMultiplier = b.damageMultiplier;
        this.fireRateMultiplier = b.fireRateMultiplier;
        this.hipfireAccuracyMultiplier = b.hipfireAccuracyMultiplier;
        this.ergonomics = b.ergonomics;
        this.bulletSpeed = b.bulletSpeed;
        this.recoilMultiplier = b.recoilMultiplier;
        this.recoilRecovery = b.recoilRecovery;
        this.gasSuppression = b.gasSuppression;
        this.gunType = b.gunType;
        this.fireModes = b.fireModes == null ? null : List.copyOf(b.fireModes);
        this.fireSound = b.fireSound;
        this.gunName = b.gunName;
        this.baseRecoilPitch = b.baseRecoilPitch;
        this.baseRecoilYaw = b.baseRecoilYaw;
        this.feedType = b.feedType;
        this.loadAmount = b.loadAmount;
        this.clipSize = b.clipSize;
        this.supplyType = b.supplyType;
        this.airCapacity = b.airCapacity;
        this.airPerShot = b.airPerShot;
        this.aimZoom = b.aimZoom;
        this.tacticalAimZoom = b.tacticalAimZoom;
        this.maskPath = b.maskPath;
        this.defaultColors = b.defaultColors;
        this.attachmentPoints = List.copyOf(b.attachmentPoints);
        this.positions = List.copyOf(b.positions);
    }

    public static Builder builder(ResourceLocation id, ModuleType type) {
        return new Builder(id, type);
    }

    public static ModuleDefinition fromJson(ResourceLocation id, JsonObject json) {
        ModuleType type = ModuleType.byName(GsonHelper.getAsString(json, "module_type"), null);
        if (type == null) throw new IllegalArgumentException("unknown module_type in " + id);
        Builder b = builder(id, type);
        if (json.has("module_affected")) {
            for (JsonElement el : json.getAsJsonArray("module_affected")) {
                JsonObject o = el.getAsJsonObject();
                ModuleType t = ModuleType.byName(GsonHelper.getAsString(o, "module_type"), null);
                AffectedMode mode;
                switch (GsonHelper.getAsString(o, "mode", "exclude")) {
                    case "include" -> mode = AffectedMode.INCLUDE;
                    case "keep_empty" -> mode = AffectedMode.KEEP_EMPTY;
                    case "not_empty" -> mode = AffectedMode.NOT_EMPTY;
                    default -> mode = AffectedMode.EXCLUDE;
                }
                List<ResourceLocation> value = new ArrayList<>();
                if (o.has("value")) {
                    for (JsonElement v : o.getAsJsonArray("value")) {
                        value.add(ResourceLocation.tryParse(v.getAsString()));
                    }
                }
                b.addAffected(t, mode, value);
            }
        }
        JsonObject props = json.has("gun_properties") ? json.getAsJsonObject("gun_properties") : new JsonObject();
        b.reloadSpeed = GsonHelper.getAsDouble(props, "reload_speed", 0);
        b.damageMultiplier = GsonHelper.getAsDouble(props, "damage_multiplier", 0);
        b.fireRateMultiplier = GsonHelper.getAsDouble(props, "fire_rate_multiplier", 0);
        b.hipfireAccuracyMultiplier = GsonHelper.getAsDouble(props, "hipfire_accuracy_multiplier", 0);
        b.ergonomics = GsonHelper.getAsDouble(props, "ergonomics", 0);
        b.bulletSpeed = GsonHelper.getAsDouble(props, "bullet_speed", 0);
        b.recoilMultiplier = GsonHelper.getAsDouble(props, "recoil_multiplier", 0);
        b.recoilRecovery = GsonHelper.getAsDouble(props, "recoil_recovery", 0);
        if (type == ModuleType.MUZZLE) {
            b.gasSuppression = net.minecraft.util.Mth.clamp(
                    GsonHelper.getAsDouble(props, "gas_suppression", 0), -10, 10);
        }
        // receiver
        if (type == ModuleType.RECEIVER || type == ModuleType.BARREL) {
            if (!json.has("gun_type")) throw new IllegalArgumentException(type.getSerializedName() + " requires gun_type: " + id);
            b.gunType = GunType.byName(json.get("gun_type").getAsString(), null);
            if (b.gunType == null) throw new IllegalArgumentException("bad gun_type in " + id);
        }
        if (type == ModuleType.RECEIVER) {
            if (!json.has("fire_modes")) throw new IllegalArgumentException("receiver requires fire_modes: " + id);
            List<FireMode> modes = new ArrayList<>();
            for (JsonElement el : json.getAsJsonArray("fire_modes")) {
                modes.add(FireMode.byName(el.getAsString()));
            }
            if (modes.isEmpty()) throw new IllegalArgumentException("empty fire_modes in " + id);
            b.fireModes = modes;
            b.fireSound = GsonHelper.getAsString(json, "fire_sound", null);
            b.gunName = GsonHelper.getAsString(json, "gun_name", null);
            b.baseRecoilPitch = GsonHelper.getAsDouble(json, "base_recoil_pitch", 0);
            b.baseRecoilYaw = GsonHelper.getAsDouble(json, "base_recoil_yaw", 0);
        }
        // feed
        if (type == ModuleType.FEED) {
            b.feedType = FeedType.byName(GsonHelper.getAsString(json, "load_type"), null);
            if (b.feedType == null) throw new IllegalArgumentException("bad load_type in " + id);
            b.loadAmount = GsonHelper.getAsInt(json, "load_amount", 0);
            b.clipSize = GsonHelper.getAsInt(json, "clip_size", 1);
        }
        // supply
        if (type == ModuleType.SUPPLY) {
            b.supplyType = SupplyType.byName(GsonHelper.getAsString(json, "supply_type"), null);
            if (b.supplyType == null) throw new IllegalArgumentException("bad supply_type in " + id);
            b.airCapacity = GsonHelper.getAsInt(json, "air_capacity", 0);
            b.airPerShot = GsonHelper.getAsInt(json, "air_per_shot", 0);
        }
        // sights
        if (type == ModuleType.SIGHT) {
            b.aimZoom = GsonHelper.getAsDouble(json, "aim_zoom", 1.25);
        }
        if (type == ModuleType.TACTICAL_SIGHT) {
            b.tacticalAimZoom = GsonHelper.getAsDouble(json, "tactical_aim_zoom", 1.25);
        }
        // handguard: exposed attachment points
        if (type == ModuleType.HANDGUARD) {
            b.attachmentPoints = parsePositions(json, "attachment_points", id);
        }
        // handguard attachment: mountable positions
        if (type == ModuleType.HANDGUARD_ATTACHMENT) {
            b.positions = parsePositions(json, "positions", id);
            if (b.positions.isEmpty()) throw new IllegalArgumentException("handguard_attachment requires positions: " + id);
        }
        // appearance
        if (json.has("appearance")) {
            JsonObject appearance = json.getAsJsonObject("appearance");
            b.maskPath = GsonHelper.getAsString(appearance, "mask", null);
            if (appearance.has("default_colors")) {
                JsonArray colors = appearance.getAsJsonArray("default_colors");
                int[] arr = new int[colors.size()];
                for (int i = 0; i < colors.size(); i++) {
                    arr[i] = parseColor(colors.get(i).getAsString());
                }
                b.defaultColors = arr;
            }
        }
        return b.build();
    }

    static int parseColor(String hex) {
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        return (int) Long.parseLong(h.length() == 6 ? "ff" + h : h, 16);
    }

    private static List<HandguardPosition> parsePositions(JsonObject json, String key, ResourceLocation id) {
        List<HandguardPosition> out = new ArrayList<>();
        if (!json.has(key)) return out;
        for (JsonElement el : json.getAsJsonArray(key)) {
            HandguardPosition p = HandguardPosition.byName(el.getAsString());
            if (p == null) throw new IllegalArgumentException("bad " + key + " entry in " + id + ": " + el.getAsString());
            if (!out.contains(p)) out.add(p);
        }
        return out;
    }

    public static final class Builder {
        private final ResourceLocation id;
        private final ModuleType type;
        private final List<Affected> affected = new ArrayList<>();
        private double reloadSpeed, damageMultiplier, fireRateMultiplier,
                hipfireAccuracyMultiplier, ergonomics, bulletSpeed,
                recoilMultiplier, recoilRecovery;
        private double gasSuppression;
        @Nullable private GunType gunType;
        @Nullable private List<FireMode> fireModes;
        @Nullable private String fireSound;
        private double baseRecoilPitch, baseRecoilYaw;
        @Nullable private String gunName;
        @Nullable private FeedType feedType;
        private int loadAmount, clipSize;
        @Nullable private SupplyType supplyType;
        private int airCapacity, airPerShot;
        private double aimZoom = 1.25, tacticalAimZoom = 1.25;
        @Nullable private String maskPath;
        @Nullable private int[] defaultColors;
        private List<HandguardPosition> attachmentPoints = Collections.emptyList();
        private List<HandguardPosition> positions = Collections.emptyList();

        private Builder(ResourceLocation id, ModuleType type) {
            this.id = id;
            this.type = type;
        }

        public Builder addAffected(ModuleType t, AffectedMode mode, List<ResourceLocation> value) {
            affected.add(new Affected(t, mode, value));
            return this;
        }

        ModuleDefinition build() {
            return new ModuleDefinition(this);
        }
    }
}