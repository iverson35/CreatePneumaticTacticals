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

    public record Affected(ModuleType type, AffectedMode mode, List<ResourceLocation> value,
                           @Nullable List<java.util.regex.Pattern> regex) {
        /** id membership: exact list OR any regex matches (REGEX= entries). */
        public boolean matches(ResourceLocation moduleId) {
            if (value.contains(moduleId)) return true;
            if (regex == null) return false;
            String s = moduleId.toString();
            for (java.util.regex.Pattern p : regex) {
                if (p.matcher(s).find()) return true;
            }
            return false;
        }
    }

    public final ResourceLocation id;
    public final ModuleType type;
    public final List<Affected> affected;
    /** additive modifiers, may be empty */
    public final double reloadSpeed, damageMultiplier, fireRateMultiplier,
            hipfireAccuracyMultiplier, ergonomics, bulletSpeed,
            recoilVerticalMultiplier, recoilHorizontalMultiplier, recoilRecovery,
            gravityMultiplier, dragMultiplier;
    /** muzzle: -5..1 gas suppression; -1 = double smoke, +1 = none */
    public final double gasSuppression;
    /**
     * stats apply only once no matter how many copies are installed
     * (multi-slot stacking still allowed; duplicates just stop stacking)
     */
    public final boolean unique;
    /**
     * muzzle: gas guides (side ports). weight distributes guided particles
     * among entries; velocity_multiplier/spread_multiplier scale the puff's
     * forward speed and cone width; direction is the emission axis, an
     * (x=yaw, y=pitch) offset in DEGREES relative to the shot direction.
     * pass-through fraction of puffs skips the guides entirely.
     */
    public final List<GasGuide> gasGuides;
    public final double gasPassThrough;
    /** receiver/barrel: gun type; barrel must match the installed receiver's */
    @Nullable public final GunType gunType;
    @Nullable public final List<FireMode> fireModes;
    @Nullable public final String fireSound;
    /** receiver-only: lang key for the auto-created gun's display name */
    @Nullable public final String gunName;
    public final double baseRecoilPitch, baseRecoilYaw;
    /** receiver-only: true = the fire sound ignores the ammo's sound_pitch
     * (Create potato projectile type) and always plays at the sound's own
     * pitch; false (default) = the shot's pitch follows the ammo */
    public final boolean ignoreAmmoPitch;
    /** feed-only fields */
    @Nullable public final FeedType feedType;
    public final int loadAmount, clipSize;
    /** supply-only fields */
    @Nullable public final SupplyType supplyType;
    public final int airCapacity, airPerShot;
    /** sight-only fields */
    public final double aimZoom, tacticalAimZoom;

    /**
     * charm-only: swing physics. Chain geometry is deliberately NOT here —
     * the segment count is whatever the model ships ({@code chain_0} upwards,
     * or a single joint on the pendant bone) and the lengths come from the
     * model's bone pivots, so the numbers cannot desync from the art.
     */
    public final CharmSpec charm;

    /** handguard: exposed attachment points; empty = no attachment slots */
    public final List<HandguardPosition> attachmentPoints;
    /** handguard_attachment: positions this attachment can mount to */
    public final List<HandguardPosition> positions;

    // ---- Z-axis geometry (parsed from the module's geo JSON at load time;
    // drives the dynamic launch distance and obstruction raycast) ----
    /** barrel/receiver: loc_muzzle (barrel tip) pivot z in blocks; NaN = bone absent */
    public final float muzzleOffsetZ;
    /** barrel: loc_muzzle_attachment (device mount) pivot z in blocks; NaN = absent */
    public final float muzzleAttachmentZ;
    /** receiver: loc_barrel pivot z in blocks; NaN = absent */
    public final float locBarrelZ;
    /** muzzle device: minimum cube z (front face) in blocks; NaN = absent */
    public final float frontZ;

    /** mutable carrier used by GunLength.parse during definition loading */
    public static final class GeometryZ {
        public float locMuzzleZ = Float.NaN;
        public float locMuzzleAttachmentZ = Float.NaN;
        public float locBarrelZ = Float.NaN;
        public float frontZ = Float.NaN;
    }

    /** one gas-guide port of a muzzle device */
    public record GasGuide(double weight, double velocityMultiplier,
                           double spreadMultiplier, double directionX, double directionY) {}

    /**
     * Charm swing physics (JSON {@code "charm"} block). Every value is
     * frame-rate independent, and {@link #DEFAULT} is what an omitted block
     * (or an omitted key) falls back to.
     *
     * @param gravityScale    gravity multiplier; 0 = weightless, &gt;1 = heavier
     * @param airDrag         velocity damping per second: {@code v *= exp(-airDrag*dt)}
     * @param pendantMass     pendant mass relative to one chain link
     * @param maxSwingDegrees swing cone half-angle clamp (degrees, off straight
     *                        down in world space; the model's rest pose must
     *                        hang along that axis, which is what a level gun
     *                        makes the charm's local -Z)
     * @param surfaceMargin   minimum gap kept to the receiver surface, blocks
     * @param bounce          normal restitution when a link hits the receiver
     * @param friction        tangential velocity kept on contact (1 = frictionless)
     */
    public record CharmSpec(double gravityScale, double airDrag, double pendantMass,
                            double maxSwingDegrees, double surfaceMargin,
                            double bounce, double friction) {
        public static final CharmSpec DEFAULT = new CharmSpec(1.0, 0.3, 3.0, 75.0, 0.02, 0.15, 0.6);
    }

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
        this.recoilVerticalMultiplier = b.recoilVerticalMultiplier;
        this.recoilHorizontalMultiplier = b.recoilHorizontalMultiplier;
        this.recoilRecovery = b.recoilRecovery;
        this.gravityMultiplier = b.gravityMultiplier;
        this.dragMultiplier = b.dragMultiplier;
        this.gasSuppression = b.gasSuppression;
        this.unique = b.unique;
        this.gasGuides = b.gasGuides;
        this.gasPassThrough = b.gasPassThrough;
        this.gunType = b.gunType;
        this.fireModes = b.fireModes == null ? null : List.copyOf(b.fireModes);
        this.fireSound = b.fireSound;
        this.gunName = b.gunName;
        this.baseRecoilPitch = b.baseRecoilPitch;
        this.baseRecoilYaw = b.baseRecoilYaw;
        this.ignoreAmmoPitch = b.ignoreAmmoPitch;
        this.feedType = b.feedType;
        this.loadAmount = b.loadAmount;
        this.clipSize = b.clipSize;
        this.supplyType = b.supplyType;
        this.airCapacity = b.airCapacity;
        this.airPerShot = b.airPerShot;
        this.aimZoom = b.aimZoom;
        this.tacticalAimZoom = b.tacticalAimZoom;
        this.charm = b.charm;

        this.attachmentPoints = List.copyOf(b.attachmentPoints);
        this.positions = List.copyOf(b.positions);
        this.muzzleOffsetZ = b.muzzleOffsetZ;
        this.muzzleAttachmentZ = b.muzzleAttachmentZ;
        this.locBarrelZ = b.locBarrelZ;
        this.frontZ = b.frontZ;
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
                List<java.util.regex.Pattern> regex = null;
                if (o.has("value")) {
                    for (JsonElement v : o.getAsJsonArray("value")) {
                        String entry = v.getAsString();
                        // "REGEX=<pattern>" entries: id match against the
                        // pattern via Affected.matches (find, not matches);
                        // anything else is an exact module id
                        if (entry.startsWith("REGEX=")) {
                            if (regex == null) regex = new ArrayList<>();
                            regex.add(java.util.regex.Pattern.compile(entry.substring("REGEX=".length())));
                        } else {
                            value.add(ResourceLocation.tryParse(entry));
                        }
                    }
                }
                b.addAffected(t, mode, value, regex);
            }
        }
        JsonObject props = json.has("gun_properties") ? json.getAsJsonObject("gun_properties") : new JsonObject();
        b.reloadSpeed = GsonHelper.getAsDouble(props, "reload_speed", 0);
        b.damageMultiplier = GsonHelper.getAsDouble(props, "damage_multiplier", 0);
        b.fireRateMultiplier = GsonHelper.getAsDouble(props, "fire_rate_multiplier", 0);
        b.hipfireAccuracyMultiplier = GsonHelper.getAsDouble(props, "hipfire_accuracy_multiplier", 0);
        b.ergonomics = GsonHelper.getAsDouble(props, "ergonomics", 0);
        b.bulletSpeed = GsonHelper.getAsDouble(props, "bullet_speed", 0);
        b.recoilVerticalMultiplier = GsonHelper.getAsDouble(props, "recoil_vertical_multiplier", 0);
        b.recoilHorizontalMultiplier = GsonHelper.getAsDouble(props, "recoil_horizontal_multiplier", 0);
        b.recoilRecovery = GsonHelper.getAsDouble(props, "recoil_recovery", 0);
        b.gravityMultiplier = GsonHelper.getAsDouble(props, "gravity_multiplier", 0);
        b.dragMultiplier = GsonHelper.getAsDouble(props, "drag_multiplier", 0);
        b.unique = GsonHelper.getAsBoolean(props, "unique", false);
        if (type == ModuleType.MUZZLE) {
            b.gasSuppression = net.minecraft.util.Mth.clamp(
                    GsonHelper.getAsDouble(props, "gas_suppression", 0), -5, 1);
            // fraction of puffs that skips the guides (fires straight ahead)
            b.gasPassThrough = net.minecraft.util.Mth.clamp(
                    GsonHelper.getAsDouble(props, "gas_pass_through", 1), 0, 1);
            if (props.has("gas_guides")) {
                for (JsonElement el : props.getAsJsonArray("gas_guides")) {
                    JsonObject g = el.getAsJsonObject();
                    b.gasGuides.add(new GasGuide(
                            GsonHelper.getAsDouble(g, "weight", 1),
                            GsonHelper.getAsDouble(g, "velocity_multiplier", 1),
                            GsonHelper.getAsDouble(g, "spread_multiplier", 1),
                            GsonHelper.getAsDouble(g, "direction_x", 0),
                            GsonHelper.getAsDouble(g, "direction_y", 0)));
                }
            }
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
            b.ignoreAmmoPitch = GsonHelper.getAsBoolean(json, "ignore_ammo_pitch", false);
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
            b.tacticalAimZoom = GsonHelper.getAsDouble(json, "tactical_aim_zoom", 1.0);
        }
        // charm: swing physics (geometry comes from the model, not the JSON)
        if (type == ModuleType.CHARM && json.has("charm")) {
            JsonObject c = json.getAsJsonObject("charm");
            CharmSpec d = CharmSpec.DEFAULT;
            b.charm = new CharmSpec(
                    GsonHelper.getAsDouble(c, "gravity_scale", d.gravityScale()),
                    GsonHelper.getAsDouble(c, "air_drag", d.airDrag()),
                    Math.max(0.01, GsonHelper.getAsDouble(c, "pendant_mass", d.pendantMass())),
                    net.minecraft.util.Mth.clamp(
                            GsonHelper.getAsDouble(c, "max_swing_degrees", d.maxSwingDegrees()), 0, 180),
                    Math.max(0, GsonHelper.getAsDouble(c, "surface_margin", d.surfaceMargin())),
                    net.minecraft.util.Mth.clamp(GsonHelper.getAsDouble(c, "bounce", d.bounce()), 0, 1),
                    net.minecraft.util.Mth.clamp(GsonHelper.getAsDouble(c, "friction", d.friction()), 0, 1));
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

        // Z-axis geometry from the module's geo JSON (pack assets, same
        // path convention as the client model); missing/unparsable file
        // leaves the NaN sentinels and GunLength keeps the 0.5 default
        ModuleDefinition.GeometryZ geoOut = new ModuleDefinition.GeometryZ();
        String geoJson = dev.ignis.createpneumatictacticals.gunpack.GunPacks.readGeoJson(id);
        if (geoJson != null) {
            try {
                dev.ignis.createpneumatictacticals.gun.GunLength.parse(geoJson,
                        type == ModuleType.MUZZLE, geoOut);
            } catch (Exception ignored) {
                // malformed geo: NaN sentinels survive, default length
            }
            if (type == ModuleType.RECEIVER && !Float.isNaN(geoOut.locBarrelZ)) {
                dev.ignis.createpneumatictacticals.gun.GunLength.noteReceiverBarrelMount(id, geoOut.locBarrelZ);
            }
        }
        return b.build();
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
                recoilVerticalMultiplier, recoilHorizontalMultiplier, recoilRecovery,
                gravityMultiplier, dragMultiplier;
        private double gasSuppression;
        private boolean unique;
        private final List<GasGuide> gasGuides = new ArrayList<>();
        private double gasPassThrough = 1;
        @Nullable private GunType gunType;
        @Nullable private List<FireMode> fireModes;
        @Nullable private String fireSound;
        private double baseRecoilPitch, baseRecoilYaw;
        private boolean ignoreAmmoPitch;
        @Nullable private String gunName;
        @Nullable private FeedType feedType;
        private int loadAmount, clipSize;
        @Nullable private SupplyType supplyType;
        private int airCapacity, airPerShot;
        private double aimZoom = 1.25, tacticalAimZoom = 1.0;
        private CharmSpec charm = CharmSpec.DEFAULT;

        private List<HandguardPosition> attachmentPoints = Collections.emptyList();
        private List<HandguardPosition> positions = Collections.emptyList();
        private float muzzleOffsetZ = Float.NaN;
        private float muzzleAttachmentZ = Float.NaN;
        private float locBarrelZ = Float.NaN;
        private float frontZ = Float.NaN;

        private Builder(ResourceLocation id, ModuleType type) {
            this.id = id;
            this.type = type;
        }

        public Builder addAffected(ModuleType t, AffectedMode mode, List<ResourceLocation> value,
                                    @Nullable List<java.util.regex.Pattern> regex) {
            affected.add(new Affected(t, mode, value, regex));
            return this;
        }

        ModuleDefinition build() {
            return new ModuleDefinition(this);
        }
    }
}