package dev.ignis.createpneumatictacticals.network;

import dev.ignis.createpneumatictacticals.ammo.AmmoExtension;
import dev.ignis.createpneumatictacticals.gun.GunNbt;
import dev.ignis.createpneumatictacticals.gun.GunStats;
import dev.ignis.createpneumatictacticals.module.FireMode;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-side handling of fire mode / ammo / aim stance cycling.
 */
public final class GunActionHandler {

    private GunActionHandler() {}

    public static void onAction(ServerPlayer player, GunActionPacket.Action action) {
        ItemStack gun = player.getMainHandItem();
        if (!(gun.getItem() instanceof dev.ignis.createpneumatictacticals.item.GunItem)) return;
        GunStats stats = GunStats.ofGun(gun);
        if (stats.receiver == null) return;

        switch (action) {
            case NEXT_FIRE_MODE -> {
                List<FireMode> modes = stats.receiver.fireModes;
                FireMode current = GunNbt.getFireMode(gun);
                if (current == null) {
                    GunNbt.setFireMode(gun, modes.get(0));
                } else {
                    int idx = modes.indexOf(current);
                    GunNbt.setFireMode(gun, modes.get((idx + 1) % modes.size()));
                }
            }
            case NEXT_AMMO -> {
                // cycle through Create potato projectile types compatible with the receiver
                List<String> compatible = compatibleAmmoIds(player, stats);

                if (compatible.isEmpty()) return;
                String current = GunNbt.getAmmo(gun);
                int idx = current == null ? -1 : compatible.indexOf(current);
                GunNbt.setAmmo(gun, compatible.get((idx + 1) % compatible.size()));
                // per plan: never auto-switch when the selected ammo runs out
            }
            case CYCLE_AIM_STANCE -> {
                String current = GunNbt.getAimStance(gun);
                String next = switch (current == null ? "" : current) {
                    case "" -> "ads";
                    case "ads" -> "tactical";
                    default -> "ads";
                };
                GunNbt.setAimStance(gun, next);
            }
        }
    }

    /**
     * Ammo types the player can actually use: inventory pods (plain pods, or
     * pressurized pods for cartridge supply) whose projectile type the
     * receiver accepts, sorted alphabetically. Creative: all compatible
     * registered types (pods not required).
     */
    private static List<String> compatibleAmmoIds(ServerPlayer player, GunStats stats) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        if (player.isCreative()) {
            var registry = player.level().registryAccess()
                    .registryOrThrow(com.simibubi.create.api.registry.CreateRegistries.POTATO_PROJECTILE_TYPE);
            for (var entry : registry.entrySet()) {
                String key = entry.getKey().location().toString();
                if (stats.receiver.gunType.accepts(AmmoExtension.get(key).gunType)) out.add(key);
            }
            return new ArrayList<>(out);
        }
        boolean cartridge = stats.supply != null && stats.supply.supplyType
                == dev.ignis.createpneumatictacticals.module.SupplyType.CARTRIDGE;
        net.minecraft.world.item.Item requiredPod = cartridge
                ? dev.ignis.createpneumatictacticals.item.ModItems.PRESSURIZED_POD.get()
                : dev.ignis.createpneumatictacticals.item.ModItems.POD.get();
        for (ItemStack stack : player.getInventory().items) {
            if (stack.getItem() != requiredPod) continue;
            var content = dev.ignis.createpneumatictacticals.item.PodItem.contentId(stack);
            if (content == null) continue;
            net.minecraft.world.item.Item item =
                    net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(content);
            if (item == null || item == net.minecraft.world.item.Items.AIR) continue;
            var typeRef = com.simibubi.create.api.equipment.potatoCannon.PotatoCannonProjectileType
                    .getTypeForItem(player.level().registryAccess(), item);
            if (typeRef.isEmpty()) continue;
            String typeId = typeRef.get().unwrapKey().orElseThrow().location().toString();
            if (stats.receiver.gunType.accepts(AmmoExtension.get(typeId).gunType)) out.add(typeId);
        }
        return new ArrayList<>(out);
    }
}