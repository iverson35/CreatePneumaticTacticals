package dev.ignis.createpneumatictacticals.gun;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ignis.createpneumatictacticals.module.ModuleDefinition;
import dev.ignis.createpneumatictacticals.module.ModuleType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * Dynamic gun length, measured on the Z axis only (the firing axis; X/Y only
 * describe the gun's girth and never move the muzzle along the shot line).
 *
 * <p>Computed from the module models' geo JSON at definition-load time (both
 * sides read the same gunpack files, no GeckoLib/client dependency), summed
 * along the receiver -> barrel -> muzzle-device bone chain:
 * <pre>
 *   length = receiver.loc_barrel.z
 *          + (barrel.loc_muzzle.z                 bare barrel, or
 *             barrel.loc_muzzle_attachment.z      with a device mounted:
 *               + device.loc_muzzle.z            device port bone, or
 *               + min cube z                      device front face)
 * </pre>
 * all pivots in px, /16 to blocks. Devices point toward -Z; "front" is the
 * most negative z, mirroring GunModulesLayer.captureMuzzleAnchor's cube
 * fallback. Guns whose models lack the bones keep the legacy 0.5-block
 * default so existing packs don't change behavior.
 */
public final class GunLength {

    /** legacy default when the bone chain is absent (matches the old constants) */
    public static final float DEFAULT = 0.5f;

    /** z of receiver.loc_barrel in blocks; NaN sentinel handled inside */
    public static float barrelMountZ(ResourceLocation receiverId) {
        Float z = RECEIVER_BARREL_MOUNT.get(receiverId);
        return z == null ? DEFAULT : z;
    }

    /** load-time hook from ModuleDefinition.fromJson: remember a receiver's
     * loc_barrel pivot so GunLength.of can resolve it without re-reading geo */
    public static void noteReceiverBarrelMount(ResourceLocation receiverId, float z) {
        RECEIVER_BARREL_MOUNT.put(receiverId, z);
    }

    /**
     * The gun's muzzle distance in blocks: how far the launch point sits
     * along the shot ray, and how far the obstruction raycast reaches.
     */
    public static float of(ItemStack gun) {
        Map<ModuleType, ModuleDefinition> modules = GunNbt.readModules(gun);
        return of(modules);
    }

    public static float of(Map<ModuleType, ModuleDefinition> modules) {
        ModuleDefinition receiver = modules.get(ModuleType.RECEIVER);
        if (receiver == null) return DEFAULT;
        float z = barrelMountZ(receiver.id);
        if (z == DEFAULT || z <= 0f) return DEFAULT; // no loc_barrel bone or degenerate
        ModuleDefinition barrel = modules.get(ModuleType.BARREL);
        if (barrel == null || Float.isNaN(barrel.muzzleOffsetZ)) return DEFAULT;
        ModuleDefinition muzzle = modules.get(ModuleType.MUZZLE);
        // bone pivots point toward -Z (muzzle direction), so the chain sum
        // is negative; the gun LENGTH is its absolute value
        if (muzzle == null) {
            // bare barrel: tip at the barrel's own loc_muzzle bone
            return Math.abs(z + barrel.muzzleOffsetZ);
        }
        if (Float.isNaN(barrel.muzzleAttachmentZ)) return DEFAULT;
        if (!Float.isNaN(muzzle.muzzleOffsetZ)) {
            // device marks its port with a loc_muzzle bone
            return Math.abs(z + barrel.muzzleAttachmentZ + muzzle.muzzleOffsetZ);
        }
        if (!Float.isNaN(muzzle.frontZ)) {
            // no port bone: device front face from its cubes
            return Math.abs(z + barrel.muzzleAttachmentZ + muzzle.frontZ);
        }
        return DEFAULT;
    }

    // ---- geo JSON parsing (definition-load time) ----------------------------

    /** receiver id -> loc_barrel pivot z (blocks); filled at module load */
    private static final Map<ResourceLocation, Float> RECEIVER_BARREL_MOUNT = new HashMap<>();

    /**
     * Parses one geo JSON into the Z-axis measurements a module definition
     * needs. Missing bones leave NaN sentinels in {@code out}; callers
     * treat NaN as "geometry unknown" and GunLength falls back to the
     * legacy 0.5 default.
     *
     * @param measureFront true when this module may be a muzzle device
     *                     without a loc_muzzle bone: also measures the
     *                     minimum cube z (front face)
     */
    public static void parse(String geoJson, boolean measureFront,
                             ModuleDefinition.GeometryZ out) {
        JsonObject root = JsonParser.parseString(geoJson).getAsJsonObject();
        Map<String, JsonObject> bones = new HashMap<>();
        collectBones(root, bones);
        Float muzzleZ = pivotZ(bones, "loc_muzzle");
        Float attachZ = pivotZ(bones, "loc_muzzle_attachment");
        Float barrelMountZ = pivotZ(bones, "loc_barrel");
        out.locMuzzleZ = muzzleZ == null ? Float.NaN : muzzleZ / 16f;
        out.locMuzzleAttachmentZ = attachZ == null ? Float.NaN : attachZ / 16f;
        out.locBarrelZ = barrelMountZ == null ? Float.NaN : barrelMountZ / 16f;
        out.frontZ = measureFront ? frontCubeZ(bones) : Float.NaN;
    }

    /** recursively index bones by name across all geometries */
    private static void collectBones(JsonObject root, Map<String, JsonObject> bones) {
        for (JsonElement geo : root.getAsJsonArray("minecraft:geometry")) {
            for (JsonElement b : geo.getAsJsonObject().getAsJsonArray("bones")) {
                JsonObject bone = b.getAsJsonObject();
                bones.put(bone.get("name").getAsString(), bone);
            }
        }
    }

    private static Float pivotZ(Map<String, JsonObject> bones, String name) {
        JsonObject bone = bones.get(name);
        if (bone == null || !bone.has("pivot")) return null;
        JsonArray pivot = bone.getAsJsonArray("pivot");
        return pivot.size() >= 3 ? (float) pivot.get(2).getAsDouble() : null;
    }

    /**
     * Minimum z over every cube's front-most vertex, bone pivots summed
     * through the parent chain (bone pivots are parent-relative px; only
     * leaf cubes convert to blocks). Mirrors GunModulesLayer.frontZ.
     */
    private static float frontCubeZ(Map<String, JsonObject> bones) {
        // build parent links: Blockbench nests bones under "children"
        Map<String, String> parentOf = new HashMap<>();
        for (JsonObject bone : bones.values()) {
            if (!bone.has("children")) continue;
            for (JsonElement child : bone.getAsJsonArray("children")) {
                parentOf.put(child.getAsString(), bone.get("name").getAsString());
            }
        }
        float best = Float.POSITIVE_INFINITY;
        for (JsonObject bone : bones.values()) {
            if (!bone.has("cubes")) continue;
            float chainZ = 0f;
            for (String name = bone.get("name").getAsString(); name != null; ) {
                JsonObject b = bones.get(name);
                if (b == null) break;
                if (b.has("pivot")) chainZ += b.getAsJsonArray("pivot").get(2).getAsDouble();
                name = parentOf.get(name);
            }
            for (JsonElement c : bone.getAsJsonArray("cubes")) {
                JsonObject cube = c.getAsJsonObject();
                double originZ = cube.has("origin") ? cube.getAsJsonArray("origin").get(2).getAsDouble() : 0;
                // origin is the min corner (Blockbench convention); in the
                // -Z-pointing device frame the front face IS that min z
                best = Math.min(best, (float) (chainZ + originZ) / 16f);
            }
        }
        return best == Float.POSITIVE_INFINITY ? Float.NaN : best;
    }

    /** reload hook: called by ModuleManager on (re)load */
    public static void clearReceiverCache() {
        RECEIVER_BARREL_MOUNT.clear();
    }
}