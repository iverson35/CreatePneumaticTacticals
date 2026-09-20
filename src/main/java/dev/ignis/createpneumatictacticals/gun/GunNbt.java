package dev.ignis.createpneumatictacticals.gun;

import dev.ignis.createpneumatictacticals.module.FireMode;
import dev.ignis.createpneumatictacticals.module.HandguardPosition;
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
 *   Modules: CompoundTag slotKey -> module definition id. Single-slot types
 *            use their type name ("receiver", "feed", ...); handguard
 *            attachments use HandguardPosition.slotKey() ("hg_top", ...).
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

    /** single-slot modules only; handguard attachments via {@link #readHandguardAttachments} */
    public static Map<ModuleType, ModuleDefinition> readModules(ItemStack stack) {
        Map<ModuleType, ModuleDefinition> out = new EnumMap<>(ModuleType.class);
        CompoundTag modules = modulesTag(stack);
        for (String key : modules.getAllKeys()) {
            if (key.startsWith("hg_")) continue;
            ModuleDefinition def = ModuleManager.get(ResourceLocation.tryParse(modules.getString(key)));
            if (def != null && def.type != ModuleType.HANDGUARD_ATTACHMENT) {
                out.put(def.type, def);
            }
        }
        return out;
    }

    /** position-bound handguard attachments */
    public static Map<HandguardPosition, ModuleDefinition> readHandguardAttachments(ItemStack stack) {
        Map<HandguardPosition, ModuleDefinition> out = new EnumMap<>(HandguardPosition.class);
        CompoundTag modules = modulesTag(stack);
        for (HandguardPosition pos : HandguardPosition.values()) {
            if (!modules.contains(pos.slotKey())) continue;
            ModuleDefinition def = ModuleManager.get(ResourceLocation.tryParse(modules.getString(pos.slotKey())));
            if (def != null && def.type == ModuleType.HANDGUARD_ATTACHMENT) {
                out.put(pos, def);
            }
        }
        return out;
    }

    public static void writeModules(ItemStack stack, Map<ModuleType, ModuleDefinition> modules,
                                    Map<HandguardPosition, ModuleDefinition> hgAttachments) {
        CompoundTag tag = new CompoundTag();
        for (Map.Entry<ModuleType, ModuleDefinition> e : modules.entrySet()) {
            if (e.getKey() == ModuleType.HANDGUARD_ATTACHMENT) continue;
            tag.putString(e.getKey().getSerializedName(), e.getValue().id.toString());
        }
        for (Map.Entry<HandguardPosition, ModuleDefinition> e : hgAttachments.entrySet()) {
            tag.putString(e.getKey().slotKey(), e.getValue().id.toString());
        }
        root(stack).put(KEY_MODULES, tag);
    }

    private static CompoundTag modulesTag(ItemStack stack) {
        CompoundTag root = root(stack);
        return root.contains(KEY_MODULES, Tag.TAG_COMPOUND) ? root.getCompound(KEY_MODULES) : new CompoundTag();
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
        if (arr.length < 3) arr = new int[]{-1, -1, -1}; // -1 = undyed
        arr[region] = argb;
        colors.putIntArray(key, arr);
        root(stack).put(KEY_COLORS, colors);
    }

    @Nullable
    public static int[] getColors(ItemStack stack, ResourceLocation moduleId) {
        CompoundTag colors = root(stack).getCompound(KEY_COLORS);
        if (!colors.contains(moduleId.toString())) return null;
        int[] arr = colors.getIntArray(moduleId.toString());
        return arr.length >= 3 ? arr : null;
    }

    /** Drops the gun's render copy of a module's colors (the item is the
     *  authority — an undyed module must clear any stale gun-side color). */
    public static void clearColors(ItemStack stack, ResourceLocation moduleId) {
        CompoundTag root = stack.getTag();
        if (root == null || !root.contains(KEY_COLORS)) return;
        CompoundTag colors = root.getCompound(KEY_COLORS);
        colors.remove(moduleId.toString());
        if (colors.isEmpty()) root.remove(KEY_COLORS);
    }
    // --- assembly validation ---

    /**
     * Validates a candidate module against the current installation
     * (no handguard attachments visible — legacy convenience overload).
     * @return null if legal, otherwise a human-readable reason (lang key suffix).
     */
    @Nullable
    public static String validate(Map<ModuleType, ModuleDefinition> installed, ModuleDefinition candidate) {
        return validate(installed, java.util.List.of(), candidate);
    }

    /**
     * Full bidirectional validation: installed modules' rules vs candidate AND
     * candidate's rules vs installed modules. hgAttachments = installed
     * handguard attachments (they live outside the single-slot map; both rule
     * directions see them).
     * @return null if legal, otherwise a human-readable reason (lang key suffix).
     */
    @Nullable
    public static String validate(Map<ModuleType, ModuleDefinition> installed,
                                  java.util.Collection<ModuleDefinition> hgAttachments,
                                  ModuleDefinition candidate) {
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
        String forward = checkAffectedRules(mergedView(installed, hgAttachments), candidate);
        return forward != null ? forward : checkCandidateRules(installed, hgAttachments, candidate);
    }

    /**
     * Validates a handguard attachment for a specific position: the handguard
     * must be installed and expose the position, the attachment must support it.
     * @return null if legal, otherwise a human-readable reason (lang key suffix).
     */
    @Nullable
    public static String validateHandguardAttachment(Map<ModuleType, ModuleDefinition> installed,
                                                     java.util.Collection<ModuleDefinition> hgAttachments,
                                                     HandguardPosition pos, ModuleDefinition candidate) {
        if (candidate.type != ModuleType.HANDGUARD_ATTACHMENT) return "wrong_type";
        ModuleDefinition handguard = installed.get(ModuleType.HANDGUARD);
        if (handguard == null || !handguard.attachmentPoints.contains(pos)) return "no_mount_point";
        if (!candidate.positions.contains(pos)) return "wrong_position";
        String forward = checkAffectedRules(mergedView(installed, hgAttachments), candidate);
        return forward != null ? forward : checkCandidateRules(installed, hgAttachments, candidate);
    }

    /** single-slot modules + installed handguard attachments, as a flat view */
    private static java.util.Collection<ModuleDefinition> mergedView(Map<ModuleType, ModuleDefinition> installed,
                                                                     java.util.Collection<ModuleDefinition> hgAttachments) {
        java.util.List<ModuleDefinition> all = new java.util.ArrayList<>(installed.values());
        all.addAll(hgAttachments);
        return all;
    }

    /** module_affected rules of all installed modules (incl. handguard attachments) vs the candidate */
    @Nullable
    private static String checkAffectedRules(java.util.Collection<ModuleDefinition> installed, ModuleDefinition candidate) {
        for (ModuleDefinition existing : installed) {
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

    /**
     * Reverse direction: the candidate's own module_affected rules vs the
     * already-installed modules. NOT_EMPTY is a completion constraint, not an
     * insertion constraint — skipped here.
     */
    @Nullable
    private static String checkCandidateRules(Map<ModuleType, ModuleDefinition> installed,
                                              java.util.Collection<ModuleDefinition> hgAttachments,
                                              ModuleDefinition candidate) {
        for (ModuleDefinition.Affected rule : candidate.affected) {
            if (rule.type() == ModuleType.HANDGUARD_ATTACHMENT) {
                String r = checkAgainstHgAttachments(rule, hgAttachments);
                if (r != null) return r;
                continue;
            }
            ModuleDefinition existing = installed.get(rule.type());
            switch (rule.mode()) {
                case EXCLUDE -> {
                    if (existing != null && rule.value().contains(existing.id)) return "excludes_installed";
                }
                case INCLUDE -> {
                    if (existing != null && !rule.value().contains(existing.id)) return "requires_other";
                }
                case KEEP_EMPTY -> {
                    if (existing != null) return "requires_empty";
                }
                case NOT_EMPTY -> { /* completion-time only */ }
            }
        }
        return null;
    }

    /** candidate rule targeting handguard_attachment, checked against every installed attachment */
    @Nullable
    private static String checkAgainstHgAttachments(ModuleDefinition.Affected rule,
                                                    java.util.Collection<ModuleDefinition> hgAttachments) {
        switch (rule.mode()) {
            case EXCLUDE -> {
                for (ModuleDefinition att : hgAttachments) {
                    if (rule.value().contains(att.id)) return "excludes_installed";
                }
            }
            case INCLUDE -> {
                for (ModuleDefinition att : hgAttachments) {
                    if (!rule.value().contains(att.id)) return "requires_other";
                }
            }
            case KEEP_EMPTY -> {
                if (!hgAttachments.isEmpty()) return "requires_empty";
            }
            case NOT_EMPTY -> { /* completion-time only */ }
        }
        return null;
    }
}