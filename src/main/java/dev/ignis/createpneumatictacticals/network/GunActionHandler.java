package dev.ignis.createpneumatictacticals.network;

import dev.ignis.createpneumatictacticals.ammo.AmmoExtension;
import dev.ignis.createpneumatictacticals.gun.AmmoTypes;
import dev.ignis.createpneumatictacticals.gun.GunNbt;
import dev.ignis.createpneumatictacticals.gun.GunStats;
import dev.ignis.createpneumatictacticals.item.ModItems;
import dev.ignis.createpneumatictacticals.item.PodItem;
import dev.ignis.createpneumatictacticals.module.FireMode;
import dev.ignis.createpneumatictacticals.module.SupplyType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Server-side handling of fire mode / aim stance cycling and of ammo picks
 * (the wheel's explicit pick, or the client's cycle target). A pick that
 * changes the type swaps the magazine: the remaining rounds go back to the
 * player and the new type loads with the reload the client starts alongside.
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
            case CYCLE_AIM_STANCE -> {
                String current = GunNbt.getAimStance(gun);
                String next = switch (current == null ? "" : current) {
                    case "" -> "ads";
                    case "ads" -> "tactical";
                    default -> "ads";
                };
                GunNbt.setAimStance(gun, next);
            }
            case CANCEL_AMMO_SWAP -> {
                // the reload that would have applied a swap was aborted: the gun
                // keeps its loaded type (the rounds were returned when the pick
                // was made). Harmless when no swap is pending.
                GunNbt.setPendingAmmo(gun, null);
            }
        }
    }

    /** Ammo pick (wheel / cycle target): only ids the player can load are accepted. */
    public static void onSelectAmmo(ServerPlayer player, String ammoId) {
        ItemStack gun = player.getMainHandItem();
        if (!(gun.getItem() instanceof dev.ignis.createpneumatictacticals.item.GunItem)) return;
        GunStats stats = GunStats.ofGun(gun);
        if (stats.receiver == null) return;
        if (!AmmoTypes.compatibleFor(player, stats).contains(ammoId)) return;
        applyAmmoSelection(player, gun, stats, ammoId);
    }

    /**
     * Write an ammo pick. With rounds of the loaded type still in the magazine
     * the pick is a swap: those rounds go back to the player as pods (whatever
     * does not fit drops at their feet), the magazine empties, and the new type
     * is deferred to the reload the client starts with the pick — so an
     * interrupted reload cancels the switch: the gun keeps its loaded type and
     * the returned rounds stay with the player. Creative hands nothing back —
     * the magazine refills for free there, so pods would only clutter the
     * inventory. Backpack feed has no magazine state and switches instantly.
     */
    private static void applyAmmoSelection(ServerPlayer player, ItemStack gun, GunStats stats, String ammoId) {
        boolean backpack = stats.feed != null
                && stats.feed.feedType == dev.ignis.createpneumatictacticals.module.FeedType.BACKPACK;
        if (backpack) {
            GunNbt.setPendingAmmo(gun, null);
            GunNbt.setAmmo(gun, ammoId);
            return;
        }
        if (ammoId.equals(GunNbt.getAmmo(gun))) {
            // picking what is already loaded: only a deferred pick is dropped
            GunNbt.setPendingAmmo(gun, null);
            return;
        }
        int rounds = GunNbt.getAmmoCount(gun);
        if (rounds > 0) {
            // creative: nothing to hand back, the magazine refills for free.
            // Otherwise: cannot hand the rounds back -> keep the gun untouched
            // rather than lose them.
            if (!player.isCreative() && !unloadMagazine(player, gun, stats, rounds)) return;
            GunNbt.setAmmoCount(gun, 0);
            GunNbt.setPendingAmmo(gun, ammoId);
        } else {
            GunNbt.setPendingAmmo(gun, null);
            GunNbt.setAmmo(gun, ammoId);
        }
        // per plan: never auto-switch when the selected ammo runs out
    }

    /**
     * Hand the magazine's remaining rounds back as pods of the type being
     * unloaded — the exact inverse of the reload's pod consumption (one pod per
     * round). Overflow past a full inventory drops at the player's feet.
     * False when the loaded type has no content item to put in a pod.
     *
     * Deliberate: the overflow is dropped and reported, never refused — the
     * swap itself must not be blocked by a full inventory (no "make room
     * first" prompt, no aborted ammo switch).
     */
    private static boolean unloadMagazine(ServerPlayer player, ItemStack gun, GunStats stats, int rounds) {
        String loaded = GunNbt.getAmmo(gun);
        if (loaded == null || loaded.isEmpty()) return false;
        Item content = AmmoExtension.contentItemFor(player.level().registryAccess(), loaded);
        if (content == null || content == Items.AIR) return false;
        boolean cartridge = stats.supply != null && stats.supply.supplyType == SupplyType.CARTRIDGE;
        ItemStack template = PodItem.ofContent(content,
                cartridge ? ModItems.PRESSURIZED_POD.get() : ModItems.POD.get());
        int perStack = Math.max(1, template.getMaxStackSize());
        int dropped = 0;
        for (int left = rounds; left > 0; left -= perStack) {
            ItemStack pods = template.copy();
            pods.setCount(Math.min(left, perStack));
            player.getInventory().add(pods); // shrinks to whatever did not fit
            if (!pods.isEmpty()) {
                dropped += pods.getCount();
                player.drop(pods, false);
            }
        }
        if (dropped > 0) {
            player.displayClientMessage(Component.translatable(
                    "gui.createpneumatictacticals.ammo_dropped", dropped), true);
        }
        return true;
    }
}
