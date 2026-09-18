package dev.ignis.createpneumatictacticals.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S: reload finished (or aborted). Server validates that enough ammo
 * exists in inventory and writes the resulting magazine count to gun NBT.
 * ammoConsumed <= 0 means the reload was aborted.
 */
public class ReloadResultPacket {

    public final boolean completed;
    public final int ammoLoaded;

    public ReloadResultPacket(boolean completed, int ammoLoaded) {
        this.completed = completed;
        this.ammoLoaded = ammoLoaded;
    }

    public static void encode(ReloadResultPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.completed);
        buf.writeVarInt(msg.ammoLoaded);
    }

    public static ReloadResultPacket decode(FriendlyByteBuf buf) {
        return new ReloadResultPacket(buf.readBoolean(), buf.readVarInt());
    }

    public static void handle(ReloadResultPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) {
                GunReloadHandler.onReloadResult(player, msg.completed, msg.ammoLoaded);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}