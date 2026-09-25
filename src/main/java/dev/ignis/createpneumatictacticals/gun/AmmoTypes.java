package dev.ignis.createpneumatictacticals.gun;

import dev.ignis.createpneumatictacticals.ammo.AmmoExtension;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which potato projectile types a player can actually load into the held gun:
 * inventory pods (plain pods, or pressurized pods for cartridge supply) whose
 * projectile type the receiver accepts, sorted alphabetically. Creative: all
 * compatible registered types (pods not required).
 *
 * <p>Shared by the server (cycle / wheel selection, which also validates
 * against it) and the client (the ammo wheel's contents).
 */
public final class AmmoTypes {

    private AmmoTypes() {}

    public static List<String> compatibleFor(Player player, GunStats stats) {
        Set<String> out = new TreeSet<>();
        if (stats.receiver == null || stats.receiver.gunType == null) return new ArrayList<>(out);
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
        Item requiredPod = cartridge
                ? dev.ignis.createpneumatictacticals.item.ModItems.PRESSURIZED_POD.get()
                : dev.ignis.createpneumatictacticals.item.ModItems.POD.get();
        for (ItemStack stack : player.getInventory().items) {
            if (stack.getItem() != requiredPod) continue;
            var content = dev.ignis.createpneumatictacticals.item.PodItem.contentId(stack);
            if (content == null) continue;
            Item item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(content);
            if (item == null || item == net.minecraft.world.item.Items.AIR) continue;
            var typeRef = com.simibubi.create.api.equipment.potatoCannon.PotatoCannonProjectileType
                    .getTypeForItem(player.level().registryAccess(), item);
            if (typeRef.isEmpty()) continue;
            String typeId = typeRef.get().unwrapKey().orElseThrow().location().toString();
            if (stats.receiver.gunType.accepts(AmmoExtension.get(typeId).gunType)) out.add(typeId);
        }
        // deep reserve: a box feeds its own type even with no loose pods
        for (ItemStack stack : player.getInventory().items) {
            if (!(stack.getItem() instanceof dev.ignis.createpneumatictacticals.block
                    .AmmoBoxBlockItem)) continue;
            String typeId = dev.ignis.createpneumatictacticals.block.entity.AmmoBoxBlockEntity.ammoTypeId(player.level().registryAccess(), stack);
            if (typeId != null && stats.receiver.gunType.accepts(AmmoExtension.get(typeId).gunType)) {
                ItemStack template = dev.ignis.createpneumatictacticals.block.entity.AmmoBoxBlockEntity.boxTemplate(stack);
                boolean boxedCartridge = template.getItem()
                        == dev.ignis.createpneumatictacticals.item.ModItems.PRESSURIZED_POD.get();
                if (boxedCartridge == cartridge) out.add(typeId);
            }
        }
        return new ArrayList<>(out);
    }
}
