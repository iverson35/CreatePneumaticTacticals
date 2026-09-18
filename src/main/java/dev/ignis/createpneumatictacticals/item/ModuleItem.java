package dev.ignis.createpneumatictacticals.item;

import dev.ignis.createpneumatictacticals.module.ModuleDefinition;
import dev.ignis.createpneumatictacticals.module.ModuleManager;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Single registered item representing any module; the module definition is
 * selected via the ModuleId NBT string. Registry name is NOT the definition id.
 */
public class ModuleItem extends Item {

    public static final String TAG_MODULE_ID = "ModuleId";


    /** dye color storage: CompoundTag "Colors" -> moduleId -> int[3] (matches GunNbt schema) */
    public static final String TAG_COLORS = "Colors";

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

    public static void setModuleId(ItemStack stack, @Nullable ResourceLocation id) {
        if (id == null) {
            stack.removeTagKey(TAG_MODULE_ID);
        } else {
            stack.getOrCreateTag().putString(TAG_MODULE_ID, id.toString());
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
        String nameKey = "module." + id.getNamespace() + "." + id.getPath();
        tooltip.add(Component.translatable(nameKey).withStyle(ChatFormatting.AQUA));
        if (def == null) return;
        addStatLines(tooltip, Map.of(
                "module.stat.reload_speed", def.reloadSpeed,
                "module.stat.damage_multiplier", def.damageMultiplier,
                "module.stat.fire_rate_multiplier", def.fireRateMultiplier,
                "module.stat.hipfire_accuracy_multiplier", def.hipfireAccuracyMultiplier,
                "module.stat.ergonomics", def.ergonomics,
                "module.stat.bullet_speed", def.bulletSpeed,
                "module.stat.recoil_multiplier", def.recoilMultiplier,
                "module.stat.recoil_recovery", def.recoilRecovery
        ));
    }

    private static void addStatLines(List<Component> tooltip, Map<String, Double> stats) {
        for (Map.Entry<String, Double> e : stats.entrySet()) {
            double v = e.getValue();
            if (v == 0) continue;
            String fmt = formatStat(v);
            String sign = v > 0 ? "+" : "";
            ChatFormatting color = v > 0 ? ChatFormatting.GREEN : ChatFormatting.RED;
            tooltip.add(Component.translatable(e.getKey(), sign + fmt).withStyle(color));
        }
    }

    private static String formatStat(double v) {
        if (Math.abs(v) >= 100) return String.valueOf((int) v);
        if (Math.abs(v) == Math.floor(Math.abs(v))) return String.valueOf((int) v);
        return String.format("%.1f", v);
    }
}