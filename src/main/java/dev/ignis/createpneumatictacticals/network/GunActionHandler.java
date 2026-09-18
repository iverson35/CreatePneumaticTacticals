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
        GunStats stats = GunStats.of(GunNbt.readModules(gun));
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

    private static List<String> compatibleAmmoIds(ServerPlayer player, GunStats stats) {
        List<String> out = new ArrayList<>();
        var registry = player.level().registryAccess()
                .registryOrThrow(com.simibubi.create.api.registry.CreateRegistries.POTATO_PROJECTILE_TYPE);
        for (var entry : registry.entrySet()) {
            String key = entry.getKey().location().toString();
            AmmoExtension ext = AmmoExtension.get(key);
            if (stats.receiver.gunType.accepts(ext.gunType)) {
                out.add(key);
            }
        }
        return out;
    }
}