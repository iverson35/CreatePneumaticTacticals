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

        GunStats stats = GunStats.of(GunNbt.readModules(gun));
        if (!stats.isComplete() || stats.feed == null) return;

        if (!completed) return; // aborted: nothing to validate

        if (stats.feed.feedType == FeedType.ROUND) {
            // clamp to clip size and inventory availability
            int current = GunNbt.getAmmoCount(gun);
            int max = stats.feed.clipSize;
            int wanted = Math.min(Math.max(0, ammoLoaded), max - current);
            int loaded = consumePods(player, gun, wanted);
            GunNbt.setAmmoCount(gun, current + loaded);
        } else if (stats.feed.feedType == FeedType.MAGAZINE) {
            int max = stats.feed.clipSize;
            int current = GunNbt.getAmmoCount(gun);
            int wanted = max - current;
            int loaded = consumePods(player, gun, wanted);
            GunNbt.setAmmoCount(gun, current + loaded);
        }
        // BACKPACK feed: no magazine state; nothing to do.
    }

    /** consume up to n pods matching the gun's selected ammo; returns consumed count */
    private static int consumePods(ServerPlayer player, ItemStack gun, int n) {
        if (n <= 0 || player.isCreative()) return n;
        String ammoId = GunNbt.getAmmo(gun);
        if (ammoId == null || ammoId.isEmpty()) return 0;
        int consumed = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (consumed >= n) break;
            if (stack.getItem() instanceof PodItem) {
                var typeOpt = PodItem.projectileType(player.level(), stack);
                if (typeOpt.isPresent()) {
                    int take = Math.min(n - consumed, stack.getCount());
                    stack.shrink(take);
                    consumed += take;
                }
            }
        }
        return consumed;
    }
}