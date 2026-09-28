package dev.ignis.createpneumatictacticals.item;

import dev.ignis.createpneumatictacticals.ammo.AmmoExtension;
import dev.ignis.createpneumatictacticals.gun.GunNbt;
import dev.ignis.createpneumatictacticals.gun.GunStats;
import dev.ignis.createpneumatictacticals.gun.InteractPass;
import dev.ignis.createpneumatictacticals.module.GunType;
import dev.ignis.createpneumatictacticals.module.ModuleDefinition;
import dev.ignis.createpneumatictacticals.module.ModuleType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.ChatFormatting;
import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
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
        return GunStats.ofGun(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        GunStats stats = GunStats.ofGun(stack);
        if (!stats.isComplete()) {
            tooltip.add(Component.translatable("tooltip.createpneumatictacticals.incomplete"));
        }
        String ammoId = GunNbt.getAmmo(stack);
        if (ammoId != null && !ammoId.isEmpty()) {
            tooltip.add(Component.translatable("tooltip.createpneumatictacticals.loaded_ammo",
                    ammoDisplayName(stack, level, ammoId), GunNbt.getAmmoCount(stack)));
        }
        // hold Shift: the gun workbench's default stats (caliber + the four
        // core multipliers). The workbench stats panel is the authority for
        // which stats are "default-visible"; keep the two in sync.
        if (net.minecraft.client.gui.screens.Screen.hasShiftDown() && stats.isComplete()) {
            if (stats.receiver != null && stats.receiver.gunType != null) {
                tooltip.add(Component.translatable("stat." + CreatePneumaticTacticals.MODID + ".gun_type")
                        .append(": ").append(Component.translatable("gun_type." + CreatePneumaticTacticals.MODID
                                + "." + stats.receiver.gunType.getSerializedName()))
                        .withStyle(ChatFormatting.GREEN));
            }
            // Loaded rounds replace the two multiplier lines with the numbers
            // they actually produce — "Damage Multiplier: 1.20" says nothing
            // about the round it multiplies, and RPM is what a player reads.
            AmmoPerformance loaded = loadedAmmo(stack, level, stats);
            if (loaded != null) {
                tooltip.add(stat(String.format("%.2f", loaded.damage()), "damage"));
                tooltip.add(stat(Math.round(loaded.rpm()) + " RPM", "fire_rate"));
            } else {
                tooltip.add(stat(stats.damageMultiplier, "damage_multiplier"));
                tooltip.add(stat(stats.fireRateMultiplier, "fire_rate_multiplier"));
            }
            tooltip.add(stat(stats.ergonomics, "ergonomics"));
            tooltip.add(stat(stats.recoilVerticalMultiplier, "recoil_vertical_multiplier"));
            tooltip.add(stat(stats.recoilHorizontalMultiplier, "recoil_horizontal_multiplier"));
            tooltip.add(stat(stats.gravityMultiplier, "gravity_multiplier"));
            tooltip.add(stat(stats.dragMultiplier, "drag_multiplier"));
        } else {
            tooltip.add(Component.translatable("tooltip." + CreatePneumaticTacticals.MODID + ".stats_hint")
                    .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        }
        super.appendHoverText(stack, level, tooltip, flag);
    }

    /** workbench-style stat line: "Damage: 1.20" */
    private static Component stat(double value, String statKey) {
        return stat(String.format("%.2f", value), statKey);
    }

    /** stat line with a preformatted value: "Fire Rate: 171 RPM" */
    private static Component stat(String value, String statKey) {
        return Component.translatable("stat." + CreatePneumaticTacticals.MODID + "." + statKey)
                .append(": ").append(value)
                .withStyle(ChatFormatting.GRAY);
    }

    /** a round's real fire rate (RPM) and point-blank damage in this gun */
    public record AmmoPerformance(double rpm, double damage) {}

    /**
     * What a given ammo TYPE would do in this gun, or null when the type is
     * unknown or there is no registry to look it up in. Mirrors the firing path
     * through the same two helpers as {@link #loadedAmmo}: the interval is
     * {@link AmmoExtension#fireIntervalTicks} (1200 / it is the RPM) and the
     * damage is the ammo's base damage times the gun's damage_multiplier —
     * point-blank, before falloff and headshots.
     */
    public static @Nullable AmmoPerformance ammoPerformance(@Nullable Level level, GunStats stats, String ammoId) {
        if (level == null || ammoId == null || ammoId.isEmpty()) return null;
        var type = level.registryAccess()
                .registryOrThrow(com.simibubi.create.api.registry.CreateRegistries.POTATO_PROJECTILE_TYPE)
                .get(ResourceLocation.tryParse(ammoId));
        if (type == null) return null;
        return new AmmoPerformance(
                1200.0 / AmmoExtension.fireIntervalTicks(type, stats.fireRateMultiplier),
                AmmoExtension.baseDamage(type, AmmoExtension.get(ammoId)) * stats.damageMultiplier);
    }

    /** the same numbers as one line: "Damage: 4.80 | Fire Rate: 171 RPM" */
    public static Component ammoPerformanceLine(AmmoPerformance perf) {
        return Component.translatable("gui." + CreatePneumaticTacticals.MODID + ".ammo_wheel.stats",
                stat(String.format("%.2f", perf.damage()), "damage"),
                stat(Math.round(perf.rpm()) + " RPM", "fire_rate"))
                .withStyle(ChatFormatting.GRAY);
    }

    /**
     * What the loaded round really does in this gun, or null when nothing
     * usable is loaded (no ammo selected, an empty magazine, an unknown type,
     * or no registry to look the type up in — the item tooltip can be drawn
     * without a level). Mirrors the firing path through the same two helpers:
     * the interval is {@link AmmoExtension#fireIntervalTicks} (1200 / it is the
     * RPM) and the damage is the ammo's base damage times the gun's
     * damage_multiplier — point-blank, before falloff and headshots.
     */
    private static @Nullable AmmoPerformance loadedAmmo(ItemStack gun, @Nullable Level level, GunStats stats) {
        // a magazine gun counts as loaded only with rounds in the mag; a
        // backpack feed has no count — its rounds sit in the player's backpack
        boolean backpack = stats.feed != null
                && stats.feed.feedType == dev.ignis.createpneumatictacticals.module.FeedType.BACKPACK;
        if (!backpack && GunNbt.getAmmoCount(gun) <= 0) return null;
        return ammoPerformance(level, stats, GunNbt.getAmmo(gun));
    }

    /**
     * Display name of the selected ammo TYPE: the display name of its first
     * registered content item (e.g. "Carrot"), not the raw registry key.
     */
    public static Component ammoDisplayName(ItemStack gun, @Nullable Level level, String ammoId) {
        if (level != null) {
            var type = level.registryAccess()
                    .registryOrThrow(com.simibubi.create.api.registry.CreateRegistries.POTATO_PROJECTILE_TYPE)
                    .get(ResourceLocation.tryParse(ammoId));
            if (type != null) {
                var itemName = type.items().stream().findFirst()
                        .map(h -> h.value().getDescription());
                if (itemName.isPresent()) return itemName.get();
            }
        }
        return Component.literal(ammoId);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return false;
    }

    /**
     * NBT sync (ammo count, air pressure, ...) must NOT replay the equip
     * animation — that is the visible "gun dips on every shot/aim" artifact.
     * Re-equip only when the item actually changes or the slot changed.
     */
    @Override
    public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        return slotChanged || oldStack.getItem() != newStack.getItem();
    }

    @Override
    public boolean shouldCauseBlockBreakReset(ItemStack oldStack, ItemStack newStack) {
        return oldStack.getItem() != newStack.getItem();
    }

    // --- left click is FIRE, not attack: suppress all vanilla attack paths ---

    @Override
    public boolean onLeftClickEntity(ItemStack stack, net.minecraft.world.entity.player.Player player,
                                     net.minecraft.world.entity.Entity entity) {
        return true; // cancel entity attack
    }

    @Override
    public boolean canAttackBlock(net.minecraft.world.level.block.state.BlockState state,
                                  net.minecraft.world.level.Level level,
                                  net.minecraft.core.BlockPos pos,
                                  net.minecraft.world.entity.player.Player player) {
        return false; // no block breaking
    }

    @Override
    public boolean onEntitySwing(ItemStack stack, net.minecraft.world.entity.LivingEntity entity) {
        return true; // suppress arm swing
    }

    // --- right click is AIM: consume all vanilla use paths ---
    //
    // Exception: while InteractPass is up (the interact key is running the
    // vanilla use flow) the gun must behave as if the hand were empty, or the
    // CONSUMEs below would eat the very interaction that key exists for.
    // Client side only: the server runs the block's use before the item's
    // useOn anyway, and the offhand fallback is a client-side loop decision.

    @Override
    public net.minecraft.world.InteractionResultHolder<ItemStack> use(Level level,
            net.minecraft.world.entity.player.Player player, net.minecraft.world.InteractionHand hand) {
        if (level.isClientSide && InteractPass.isActive()) {
            return net.minecraft.world.InteractionResultHolder.pass(player.getItemInHand(hand));
        }
        return net.minecraft.world.InteractionResultHolder.consume(player.getItemInHand(hand));
    }

    @Override
    public net.minecraft.world.InteractionResult useOn(net.minecraft.world.item.context.UseOnContext context) {
        if (context.getLevel().isClientSide && InteractPass.isActive()) {
            return net.minecraft.world.InteractionResult.PASS;
        }
        return net.minecraft.world.InteractionResult.CONSUME;
    }

    @Override
    public net.minecraft.world.InteractionResult interactLivingEntity(ItemStack stack,
            net.minecraft.world.entity.player.Player player, net.minecraft.world.entity.LivingEntity target,
            net.minecraft.world.InteractionHand hand) {
        if (player.level().isClientSide && InteractPass.isActive()) {
            return net.minecraft.world.InteractionResult.PASS;
        }
        return net.minecraft.world.InteractionResult.CONSUME;
    }
}