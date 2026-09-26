package dev.ignis.createpneumatictacticals.item;
import dev.ignis.createpneumatictacticals.gun.GunNbt;
import dev.ignis.createpneumatictacticals.module.ModuleDefinition;
import dev.ignis.createpneumatictacticals.module.ModuleManager;
import dev.ignis.createpneumatictacticals.module.ModuleRoll;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Single registered item representing any module; the module definition is
 * selected via the ModuleId NBT string. Registry name is NOT the definition id.
 * GeckoLib-rendered: per-stack geo/texture from the module id (ModuleGeoModel),
 * placeholder cube while the module has no art assets.
 */
public class ModuleItem extends Item implements GeoItem {

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public static final String TAG_MODULE_ID = "ModuleId";


    /** dye color storage: CompoundTag "Colors" -> moduleId -> int[3] (matches GunNbt schema) */
    public static final String TAG_COLORS = "Colors";

    /** player-hidden flag on the module item (sole authority; the gun's
     *  Hidden CompoundTag is only a render copy) */
    public static final String TAG_HIDDEN = "Hidden";

    public ModuleItem(Properties properties) {
        super(properties);
    }

    public boolean isModule(ItemStack stack) {
        return stack.getItem() instanceof ModuleItem && getModuleId(stack) != null;
    }

    @Nullable
    public static ResourceLocation getModuleId(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_MODULE_ID, CompoundTag.TAG_STRING)) return null;
        return ResourceLocation.tryParse(tag.getString(TAG_MODULE_ID));
    }

    /**
     * Obsolete-module cleanup: a module whose definition no longer exists
     * (datapack entry removed) vanishes from inventories. inventoryTick runs
     * per stack; the cost is one HashMap lookup in ModuleManager — no scans.
     * Guns drop dead ids on read in GunNbt.readModules, so installed modules
     * need no handling here.
     */
    @Override
    public void inventoryTick(ItemStack stack, Level level, net.minecraft.world.entity.Entity entity,
                              int slot, boolean selected) {
        if (level.isClientSide) return;
        ResourceLocation id = getModuleId(stack);
        if (id != null && ModuleManager.get(id) == null) {
            stack.setCount(0);
        }
    }

    /**
     * Item name = the module's own name (module.<ns>.<path>, same key the
     * workbench and the old tooltip line use) instead of the generic
     * "Module". Unassigned stacks keep the generic registry name.
     */
    @Override
    public Component getName(ItemStack stack) {
        ResourceLocation id = getModuleId(stack);
        if (id == null) return super.getName(stack);
        return Component.translatable("module." + id.getNamespace() + "." + id.getPath());
    }

    public static void setModuleId(ItemStack stack, @Nullable ResourceLocation id) {
        if (id == null) {
            stack.removeTagKey(TAG_MODULE_ID);
        } else {
            stack.getOrCreateTag().putString(TAG_MODULE_ID, id.toString());
        }
    }

    /**
     * Manufacturing roll of this stack (fractions per rollable attribute);
     * null when the stack carries none, which reads as the authored values.
     */
    @Nullable
    public static CompoundTag getRolls(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(ModuleRoll.TAG_ROLLS, CompoundTag.TAG_COMPOUND)) return null;
        CompoundTag rolls = tag.getCompound(ModuleRoll.TAG_ROLLS);
        return rolls.isEmpty() ? null : rolls.copy();
    }

    /** writes the manufacturing roll onto the stack (null clears it) */
    public static void setRolls(ItemStack stack, @Nullable CompoundTag rolls) {
        if (rolls == null || rolls.isEmpty()) stack.removeTagKey(ModuleRoll.TAG_ROLLS);
        else stack.getOrCreateTag().put(ModuleRoll.TAG_ROLLS, rolls.copy());
    }

    /**
     * Dye-region colors on a module item: the stack's own Colors NBT (set
     * by workbench dyeing). Null when absent — render undyed.
     */
    @Nullable
    public static int[] getDyeColors(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        ResourceLocation id = getModuleId(stack);
        if (id == null || tag == null) return null;
        if (!tag.contains(TAG_COLORS, CompoundTag.TAG_COMPOUND)) return null;
        int[] arr = tag.getCompound(TAG_COLORS).getIntArray(id.toString());
        return arr.length >= 3 ? arr : null;
    }

    /** Writes dye-region colors onto the stack's Colors NBT (used when the
     *  workbench gun materializes virtual module items). */
    public static void setDyeColors(ItemStack stack, int[] colors) {
        ResourceLocation id = getModuleId(stack);
        if (id == null || colors == null || colors.length < 3) return;
        CompoundTag tag = stack.getOrCreateTag();
        CompoundTag colorTag = tag.getCompound(TAG_COLORS);
        colorTag.putIntArray(id.toString(), colors);
        tag.put(TAG_COLORS, colorTag);
    }

    // --- AW skin descriptor (whole-Compound, verbatim — never parsed here) ---

    /**
     * The item's AW skin descriptor Compound ("ArmourersWorkshop" NBT key,
     * written by AW's own skinning table). Null when the item carries no
     * skin. The stack is the sole authority; the gun only keeps a render copy.
     */
    @Nullable
    public static CompoundTag getSkinTag(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(GunNbt.TAG_AW_SKIN, CompoundTag.TAG_COMPOUND)) return null;
        return tag.getCompound(GunNbt.TAG_AW_SKIN);
    }

    /** Writes (or clears, with null) the AW skin descriptor onto the stack's
     *  "ArmourersWorkshop" NBT key — used when the workbench materializes a
     *  removed module item back out of the gun's render copy. */
    public static void setSkinTag(ItemStack stack, @Nullable CompoundTag descriptorTag) {
        if (descriptorTag == null || descriptorTag.isEmpty()) {
            stack.removeTagKey(GunNbt.TAG_AW_SKIN);
        } else {
            stack.getOrCreateTag().put(GunNbt.TAG_AW_SKIN, descriptorTag.copy());
        }
    }

    // --- module visibility (this item's own Hidden byte; sole authority,
    // the gun only keeps a render copy) ---

    public static boolean isHidden(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.getBoolean(TAG_HIDDEN);
    }

    public static void setHidden(ItemStack stack, boolean hidden) {
        if (hidden) {
            stack.getOrCreateTag().putBoolean(TAG_HIDDEN, true);
        } else {
            stack.removeTagKey(TAG_HIDDEN);
        }
    }
    public static ItemStack of(ResourceLocation moduleId) {
        ItemStack stack = new ItemStack(dev.ignis.createpneumatictacticals.item.ModItems.MODULE.get());
        stack.getOrCreateTag().putString(TAG_MODULE_ID, moduleId.toString());
        return stack;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        ResourceLocation id = getModuleId(stack);
        if (id == null) {
            tooltip.add(Component.translatable("module.tooltip.unassigned").withStyle(ChatFormatting.GRAY));
            return;
        }
        ModuleDefinition def = ModuleManager.get(id);
        // no name line here: the stack's title already shows the module name
        // (getName above); the tooltip starts directly with the stats
        if (def == null) return;
        // a module with an AW skin keeps it through install/remove; surface
        // it so the skin isn't a surprise on the assembled gun
        if (getSkinTag(stack) != null) {
            tooltip.add(Component.translatable("tooltip.createpneumatictacticals.aw_skin")
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        if (def.gunType != null) {
            tooltip.add(Component.translatable("stat.createpneumatictacticals.gun_type")
                    .append(": ")
                    .append(Component.translatable("gun_type.createpneumatictacticals."
                            + def.gunType.getSerializedName()))
                    .withStyle(ChatFormatting.YELLOW));
        }
        // type-specific lines: feed type + capacity, supply type,
        // handguard slots, attachment mount positions
        if (def.type == dev.ignis.createpneumatictacticals.module.ModuleType.FEED
                && def.feedType != null) {
            var line = Component.translatable("stat.createpneumatictacticals.feed_type")
                    .append(": ")
                    .append(Component.translatable("feed_type.createpneumatictacticals."
                            + def.feedType.getSerializedName()));
            if (def.clipSize > 0) {
                line.append(Component.literal("  (")
                        .append(Component.translatable("stat.createpneumatictacticals.clip_capacity"))
                        .append(": " + def.clipSize + ")"));
            }
            tooltip.add(line.withStyle(ChatFormatting.YELLOW));
        }
        if (def.type == dev.ignis.createpneumatictacticals.module.ModuleType.SUPPLY
                && def.supplyType != null) {
            tooltip.add(Component.translatable("stat.createpneumatictacticals.supply_type")
                    .append(": ")
                    .append(Component.translatable("supply_type.createpneumatictacticals."
                            + def.supplyType.getSerializedName()))
                    .withStyle(ChatFormatting.YELLOW));
        }
        if (def.type == dev.ignis.createpneumatictacticals.module.ModuleType.HANDGUARD
                && !def.attachmentPoints.isEmpty()) {
            tooltip.add(positionLine("stat.createpneumatictacticals.hg_slots",
                    def.attachmentPoints).withStyle(ChatFormatting.YELLOW));
        }
        if (def.type == dev.ignis.createpneumatictacticals.module.ModuleType.HANDGUARD_ATTACHMENT
                && !def.positions.isEmpty()) {
            tooltip.add(positionLine("stat.createpneumatictacticals.hg_positions",
                    def.positions).withStyle(ChatFormatting.YELLOW));
        }
        // LinkedHashMap, not Map.of: past 10 pairs Map.of has no overload, and
        // its iteration order is salted per JVM run - the tooltip line order
        // would shuffle between launches.
        // rollable attributes show their effective value (authored x roll);
        // the rest are never rolled and keep the authored value
        CompoundTag rolls = getRolls(stack);
        Map<String, Double> stats = new java.util.LinkedHashMap<>();
        stats.put("reload_speed", ModuleRoll.value(def, ModuleRoll.Attr.RELOAD_SPEED, rolls));
        stats.put("damage_multiplier", def.damageMultiplier);
        stats.put("fire_rate_multiplier", def.fireRateMultiplier);
        stats.put("hipfire_accuracy_multiplier", ModuleRoll.value(def, ModuleRoll.Attr.HIPFIRE_ACCURACY, rolls));
        stats.put("ergonomics", ModuleRoll.value(def, ModuleRoll.Attr.ERGONOMICS, rolls));
        stats.put("bullet_speed", def.bulletSpeed);
        stats.put("recoil_vertical_multiplier", ModuleRoll.value(def, ModuleRoll.Attr.RECOIL_VERTICAL, rolls));
        stats.put("recoil_horizontal_multiplier", ModuleRoll.value(def, ModuleRoll.Attr.RECOIL_HORIZONTAL, rolls));
        stats.put("recoil_recovery", ModuleRoll.value(def, ModuleRoll.Attr.RECOIL_RECOVERY, rolls));
        stats.put("gravity_multiplier", def.gravityMultiplier);
        stats.put("drag_multiplier", def.dragMultiplier);
        stats.put("gas_suppression", ModuleRoll.value(def, ModuleRoll.Attr.GAS_SUPPRESSION, rolls));
        addStatLines(tooltip, stats);
    }

    /**
     * Stats where a lower value is better; everything else reads green when
     * it increases. Both recoil multipliers scale the kick directly and both
     * ballistics scales multiply gravity/drag, so a negative modifier is the
     * improvement in all four.
     */
    private static final java.util.Set<String> LOWER_IS_BETTER = java.util.Set.of(
            "recoil_vertical_multiplier", "recoil_horizontal_multiplier",
            "gravity_multiplier", "drag_multiplier");

    /**
     * Box-drawing glyph for the open-slot set: each available slot draws
     * its arm from the center. mask = up<<3 | down<<2 | left<<1 | right.
     */
    private static String slotGlyph(boolean up, boolean down, boolean left, boolean right) {
        int mask = (up ? 8 : 0) | (down ? 4 : 0) | (left ? 2 : 0) | (right ? 1 : 0);
        return switch (mask) {
            case 0b1111 -> "\u253C"; // \u2500\u2502 full cross: all four
            case 0b1011 -> "\u2534"; // up + left + right
            case 0b0111 -> "\u252C"; // down + left + right
            case 0b1101 -> "\u251C"; // up + down + right
            case 0b1110 -> "\u2524"; // up + down + left
            case 0b1100 -> "\u2502"; // up + down
            case 0b0011 -> "\u2500"; // left + right
            case 0b1010 -> "\u2518"; // up + left
            case 0b1001 -> "\u2514"; // up + right
            case 0b0110 -> "\u2510"; // down + left
            case 0b0101 -> "\u250C"; // down + right
            case 0b1000 -> "\u2191"; // up only (no single-arm glyphs in Unicode)
            case 0b0100 -> "\u2193"; // down only
            case 0b0010 -> "\u2190"; // left only
            case 0b0001 -> "\u2192"; // right only
            default -> "\u2500";
        };
    }

    /** "label: <glyph>" - single box-drawing glyph summarizing the slots */
    private static net.minecraft.network.chat.MutableComponent positionLine(String labelKey,
            java.util.List<dev.ignis.createpneumatictacticals.module.HandguardPosition> positions) {
        boolean up = false, down = false, left = false, right = false;
        for (dev.ignis.createpneumatictacticals.module.HandguardPosition p : positions) {
            switch (p) {
                case TOP -> up = true;
                case BOTTOM -> down = true;
                case LEFT -> left = true;
                case RIGHT -> right = true;
            }
        }
        return Component.translatable(labelKey)
                .append(": ")
                .append(Component.literal(slotGlyph(up, down, left, right)));
    }

    private static void addStatLines(List<Component> tooltip, Map<String, Double> stats) {
        for (Map.Entry<String, Double> e : stats.entrySet()) {
            double v = e.getValue();
            if (v == 0) continue;
            String fmt = formatStat(v);
            String sign = v > 0 ? "+" : "";
            boolean better = LOWER_IS_BETTER.contains(e.getKey()) ? v < 0 : v > 0;
            ChatFormatting color = better ? ChatFormatting.GREEN : ChatFormatting.RED;
            tooltip.add(Component.translatable("stat.createpneumatictacticals." + e.getKey())
                    .append(": ").append(sign + fmt).withStyle(color));
        }
    }

    private static String formatStat(double v) {
        if (Math.abs(v) >= 100) return String.valueOf((int) v);
        if (Math.abs(v) == Math.floor(Math.abs(v))) return String.valueOf((int) v);
        // rolls land anywhere in 0..1 of the authored value, so sub-1 stats
        // keep two decimals (one decimal would hide most of the variation)
        if (Math.abs(v) < 1) return String.format("%.2f", v);
        return String.format("%.1f", v);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar registrar) {
        // module items are static props; reload animation is driven on the gun's
        // own animatable, not the item stack
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            private dev.ignis.createpneumatictacticals.client.render.ModuleItemRenderer renderer;

            @Override
            public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) {
                    renderer = new dev.ignis.createpneumatictacticals.client.render.ModuleItemRenderer();
                }
                return renderer;
            }
        });
    }
}