package dev.ignis.createpneumatictacticals.item;

import dev.ignis.createpneumatictacticals.ammo.AmmoExtension;
import dev.ignis.createpneumatictacticals.gun.GunNbt;
import dev.ignis.createpneumatictacticals.gun.GunStats;
import dev.ignis.createpneumatictacticals.module.GunType;
import dev.ignis.createpneumatictacticals.module.ModuleDefinition;
import dev.ignis.createpneumatictacticals.module.ModuleType;
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
 * The assembled gun item. Behavior (fire/reload/use) is driven by events in
 * dev.ignis.createpneumatictacticals.gun; this class only handles identity,
 * tooltips and NBT helpers.
 */
public class GunItem extends Item {

    public GunItem(Properties properties) {
        super(properties);
    }

    public GunStats stats(ItemStack stack) {
        return GunStats.of(GunNbt.readModules(stack));
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        Map<ModuleType, ModuleDefinition> modules = GunNbt.readModules(stack);
        GunStats stats = GunStats.of(modules);
        if (!stats.isComplete()) {
            tooltip.add(Component.translatable("tooltip.createpneumatictacticals.incomplete"));
        }
        String ammoId = GunNbt.getAmmo(stack);
        if (ammoId != null && !ammoId.isEmpty()) {
            tooltip.add(Component.translatable("tooltip.createpneumatictacticals.loaded_ammo",
                    Component.translatable(ammoId), GunNbt.getAmmoCount(stack)));
        }
        super.appendHoverText(stack, level, tooltip, flag);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return false;
    }
}