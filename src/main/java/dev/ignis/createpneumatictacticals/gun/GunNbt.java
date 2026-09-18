package dev.ignis.createpneumatictacticals.gun;

import dev.ignis.createpneumatictacticals.module.FireMode;
import dev.ignis.createpneumatictacticals.module.ModuleDefinition;
import dev.ignis.createpneumatictacticals.module.ModuleManager;
import dev.ignis.createpneumatictacticals.module.ModuleType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * NBT schema for a gun ItemStack:
 *   Modules: ListTag of module definition ids (one per installed slot; handguard
 *            attachments may repeat so the list is ordered by slot convention:
 *            receiver, feed, supply, barrel, muzzle, handguard, handguard_attachment(×N),
 *            sight, tactical_sight, stock)
 *   Ammo: current potato projectile type id string (empty = none selected)
 *   AmmoCount: int rounds in magazine
 *   FireMode: string
 *   AimStance: string ("hip"|"ads"|"tactical") — per-gun memory
 *   Colors: CompoundTag module-id -> 3 packed ARGB ints (dye regions)
 */
public final class GunNbt {

    public static final String KEY_MODULES = "Modules";
    public static final String KEY_AMMO = "Ammo";
    public static final String KEY_AMMO_COUNT = "AmmoCount";
    public static final String KEY_FIRE_MODE = "FireMode";
    public static final String KEY_AIM_STANCE = "AimStance";
    public static final String KEY_COLORS = "Colors";

    private GunNbt() {}

    public static CompoundTag root(ItemStack stack) {
        return stack.getOrCreateTag();
    }

    // --- modules ---

    public static Map<ModuleType, ModuleDefinition> readModules(ItemStack stack) {
        Map<ModuleType, ModuleDefinition> out = new EnumMap<>(ModuleType.class);
        CompoundTag root = root(stack);
        if (!root.contains(KEY_MODULES, Tag.TAG_LIST)) return out;
        for (Tag t : root.getList(KEY_MODULES, Tag.TAG_STRING)) {
            ModuleDefinition def = ModuleManager.get(ResourceLocation.tryParse(t.getAsString()));
            if (def != null && !out.containsKey(def.type)) {
                out.put(def.type, def);
            }
        }
        return out;
    }

    public static void writeModules(ItemStack stack, Map<ModuleType, ModuleDefinition> modules) {
        ListTag list = new ListTag();
        for (Map.Entry<ModuleType, ModuleDefinition> e : modules.entrySet()) {
            list.add(StringTag.valueOf(e.getValue().id.toString()));
        }
        root(stack).put(KEY_MODULES, list);
    }

    // --- ammo ---

    @Nullable
    public static String getAmmo(ItemStack stack) {
        return root(stack).contains(KEY_AMMO) ? root(stack).getString(KEY_AMMO) : null;
    }

    public static void setAmmo(ItemStack stack, @Nullable String ammoId) {
        if (ammoId == null) root(stack).remove(KEY_AMMO);
        else root(stack).putString(KEY_AMMO, ammoId);
    }

    public static int getAmmoCount(ItemStack stack) {
        return root(stack).getInt(KEY_AMMO_COUNT);
    }

    public static void setAmmoCount(ItemStack stack, int count) {
        root(stack).putInt(KEY_AMMO_COUNT, Math.max(0, count));
    }

    // --- fire mode ---

    public static void setFireMode(ItemStack stack, FireMode mode) {
        root(stack).putString(KEY_FIRE_MODE, mode.getSerializedName());
    }

    @Nullable
    public static FireMode getFireMode(ItemStack stack) {
        if (!root(stack).contains(KEY_FIRE_MODE)) return null;
        try {
            return FireMode.byName(root(stack).getString(KEY_FIRE_MODE));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // --- aim stance memory ---

    public static void setAimStance(ItemStack stack, String stance) {
        root(stack).putString(KEY_AIM_STANCE, stance);
    }

    public static String getAimStance(ItemStack stack) {
        return root(stack).getString(KEY_AIM_STANCE);
    }

    // --- dye colors ---

    public static void setColor(ItemStack stack, ResourceLocation moduleId, int region, int argb) {
        CompoundTag colors = root(stack).getCompound(KEY_COLORS);
        String key = moduleId.toString();
        int[] arr = colors.getIntArray(key);
        if (arr.length < 3) arr = new int[]{0xFF8B8B8B, 0xFF3A3A3A, 0xFFC0C0C0};
        arr[region] = argb;
        colors.putIntArray(key, arr);
        root(stack).put(KEY_COLORS, colors);
    }

    @Nullable
    public static int[] getColors(ItemStack stack, ResourceLocation moduleId) {
        CompoundTag colors = root(stack).getCompound(KEY_COLORS);
        if (!colors.contains(moduleId.toString())) return null;
        return colors.getIntArray(moduleId.toString());
    }

    // --- assembly validation ---

    /**
     * Validates a candidate module against the current installation.
     * @return null if legal, otherwise a human-readable reason (lang key suffix).
     */
    @Nullable
    public static String validate(Map<ModuleType, ModuleDefinition> installed, ModuleDefinition candidate) {
        // barrel gun_type must match the receiver's gun_type (checked both directions
        // so swapping the receiver under an installed barrel is also rejected)
        ModuleDefinition receiver = candidate.type == ModuleType.RECEIVER ? candidate
                : installed.get(ModuleType.RECEIVER);
        ModuleDefinition barrel = candidate.type == ModuleType.BARREL ? candidate
                : installed.get(ModuleType.BARREL);
        if (receiver != null && barrel != null
                && receiver.gunType != null && barrel.gunType != null
                && receiver.gunType != barrel.gunType) {
            return "gun_type_mismatch";
        }
        for (ModuleDefinition existing : installed.values()) {
            if (existing.id.equals(candidate.id)) continue;
            for (ModuleDefinition.Affected rule : existing.affected) {
                if (rule.type() != candidate.type) continue;
                switch (rule.mode()) {
                    case EXCLUDE -> {
                        if (rule.value().contains(candidate.id)) return "excluded_by";
                    }
                    case INCLUDE -> {
                        if (!rule.value().contains(candidate.id)) return "not_included_by";
                    }
                    case KEEP_EMPTY -> {
                        return "must_be_empty";
                    }
                    case NOT_EMPTY -> {
                        return "required_nonempty";
                    }
                }
            }
        }
        return null;
    }
}