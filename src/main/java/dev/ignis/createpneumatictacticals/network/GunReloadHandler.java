package dev.ignis.createpneumatictacticals.network;

import dev.ignis.createpneumatictacticals.item.PodItem;
import dev.ignis.createpneumatictacticals.module.FeedType;
import dev.ignis.createpneumatictacticals.gun.GunNbt;
import dev.ignis.createpneumatictacticals.gun.GunStats;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Server-side validation of client reload results. Magazine reloads fill from
 * pods in inventory; round reloads add up to ammoLoaded rounds. Aborted
 * reloads change nothing.
 */
public final class GunReloadHandler {

    private GunReloadHandler() {}

    public static void onReloadResult(ServerPlayer player, boolean completed, int ammoLoaded) {
        ItemStack gun = player.getMainHandItem();
        if (!(gun.getItem() instanceof dev.ignis.createpneumatictacticals.item.GunItem)) return;

        GunStats stats = GunStats.ofGun(gun);
        if (!stats.isComplete() || stats.feed == null) return;

        if (!completed) return; // aborted: nothing to validate

        if (stats.feed.feedType == FeedType.ROUND || stats.feed.feedType == FeedType.MAGAZINE) {
            // ammo swap: the magazine was emptied when the pick was made, so
            // this reload is what loads the new type — promote it now
            String pending = GunNbt.getPendingAmmo(gun);
            if (pending != null) {
                GunNbt.setPendingAmmo(gun, null);
                GunNbt.setAmmo(gun, pending);
            }
            int current = GunNbt.getAmmoCount(gun);
            int max = stats.feed.clipSize;
            int wanted = stats.feed.feedType == FeedType.ROUND
                    ? Math.min(Math.max(0, ammoLoaded), max - current)
                    : max - current;
            int loaded = consumePods(player, gun, wanted);
            if (loaded == 0 && wanted > 0 && !player.isCreative()) {
                boolean cartridge = stats.supply != null && stats.supply.supplyType
                        == dev.ignis.createpneumatictacticals.module.SupplyType.CARTRIDGE;
                feedback(player, cartridge ? "no_pressurized_pod" : "no_pod");
            }
            GunNbt.setAmmoCount(gun, current + loaded);
        }
        // BACKPACK feed: no magazine state; nothing to do.
    }

    private static void feedback(ServerPlayer player, String key) {
        player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                "gui.createpneumatictacticals.fail." + key), true);
    }

    /**
     * Consume up to n pods matching the gun's selected ammo TYPE; returns the
     * loaded count. Cartridge-supply guns load pressurized pods, others plain
     * pods. Creative: loads without consuming (and without requiring pods).
     */
    private static int consumePods(ServerPlayer player, ItemStack gun, int n) {
        if (n <= 0) return 0;
        String ammoId = GunNbt.getAmmo(gun);
        if (ammoId == null || ammoId.isEmpty()) return 0;
        if (player.isCreative()) return n;
        GunStats stats = GunStats.ofGun(gun);
        boolean cartridge = stats.supply != null && stats.supply.supplyType
                == dev.ignis.createpneumatictacticals.module.SupplyType.CARTRIDGE;
        net.minecraft.world.item.Item requiredItem = cartridge
                ? dev.ignis.createpneumatictacticals.item.ModItems.PRESSURIZED_POD.get()
                : dev.ignis.createpneumatictacticals.item.ModItems.POD.get();
        int consumed = consumeLoose(player, requiredItem, ammoId, n);
        if (consumed < n && !player.isCreative()) {
            // deep reserve: boxed pods of the same type, drained box by box
            for (ItemStack stack : player.getInventory().items) {
                if (consumed >= n) break;
                if (!(stack.getItem() instanceof dev.ignis.createpneumatictacticals.block
                        .AmmoBoxBlockItem)) continue;
                if (!boxFeeds(player, stack, cartridge, ammoId)) continue;
                for (ItemStack pods : dev.ignis.createpneumatictacticals.block.entity.AmmoBoxBlockEntity.drain(stack, n - consumed)) {
                    consumed += pods.getCount();
                    if (consumed >= n) break;
                }
            }
        }
        return consumed;
    }

    /**
     * The original loose-pod scan, unchanged: up to n matching pods out of
     * the bare inventory, 1 pod = 1 round.
     */
    private static int consumeLoose(ServerPlayer player,
                                    net.minecraft.world.item.Item requiredItem, String ammoId, int n) {
        int consumed = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (consumed >= n) break;
            if (stack.getItem() != requiredItem) continue;
            var content = PodItem.contentId(stack);
            if (content == null) continue;
            // pod content is an item id; the gun selects a projectile TYPE id
            net.minecraft.world.item.Item item =
                    net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(content);
            if (item == null || item == net.minecraft.world.item.Items.AIR) continue;
            var typeRef = com.simibubi.create.api.equipment.potatoCannon.PotatoCannonProjectileType
                    .getTypeForItem(player.level().registryAccess(), item);
            if (typeRef.isEmpty()
                    || !typeRef.get().unwrapKey().orElseThrow().location().toString().equals(ammoId)) continue;
            int take = Math.min(n - consumed, stack.getCount());
            stack.shrink(take);
            consumed += take;
        }
        return consumed;
    }

    /**
     * True when {@code box} holds pressurized pods iff the gun needs them,
     * and their projectile type is the loaded one.
     */
    private static boolean boxFeeds(ServerPlayer player, ItemStack box, boolean cartridge, String ammoId) {
        ItemStack template = dev.ignis.createpneumatictacticals.block.entity.AmmoBoxBlockEntity.boxTemplate(box);
        if (template.isEmpty()) return false;
        boolean boxedCartridge = template.getItem()
                == dev.ignis.createpneumatictacticals.item.ModItems.PRESSURIZED_POD.get();
        if (boxedCartridge != cartridge) return false;
        String typeId = dev.ignis.createpneumatictacticals.block.entity.AmmoBoxBlockEntity.ammoTypeId(player.level().registryAccess(), box);
        return ammoId.equals(typeId);
    }
}