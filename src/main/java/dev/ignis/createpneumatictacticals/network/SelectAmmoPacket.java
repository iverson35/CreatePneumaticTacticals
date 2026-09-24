package dev.ignis.createpneumatictacticals.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S: the ammo wheel picked a specific type for the held gun. The server
 * validates the id against the ammo the player actually has, then writes the
 * gun NBT with the same rule as the plain cycle (instant switch, or pending
 * while the magazine still holds rounds of the old type).
 */
public class SelectAmmoPacket {

    public final String ammoId;

    public SelectAmmoPacket(String ammoId) {
        this.ammoId = ammoId;
    }

    public static void encode(SelectAmmoPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.ammoId);
    }

    public static SelectAmmoPacket decode(FriendlyByteBuf buf) {
        return new SelectAmmoPacket(buf.readUtf());
    }

    public static void handle(SelectAmmoPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) {
                try {
                    GunActionHandler.onSelectAmmo(player, msg.ammoId);
                } catch (Exception e) {
                    // enqueueWork futures swallow exceptions silently — log or
                    // a failed pick just looks like "nothing happens"
                    com.mojang.logging.LogUtils.getLogger().error(
                            "select ammo {} failed for {}", msg.ammoId, player.getName().getString(), e);
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
