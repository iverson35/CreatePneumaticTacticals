package dev.ignis.createpneumatictacticals.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S: player requests a shot. Server validates ammo/air/fire rate then spawns
 * the projectile. Payload is empty for now — server derives everything from
 * held item NBT (server-authoritative).
 */
public class FireRequestPacket {

    public FireRequestPacket() {}

    public static void encode(FireRequestPacket msg, FriendlyByteBuf buf) {}

    public static FireRequestPacket decode(FriendlyByteBuf buf) {
        return new FireRequestPacket();
    }

    public static void handle(FireRequestPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) {
                GunFireHandler.onFireRequest(player);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}