package dev.ignis.createpneumatictacticals.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** C2S: aim state edge (server needs it for spread suppression while aiming). */
public class AimStatePacket {

    public final boolean aiming;

    public AimStatePacket(boolean aiming) {
        this.aiming = aiming;
    }

    public static void encode(AimStatePacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.aiming);
    }

    public static AimStatePacket decode(FriendlyByteBuf buf) {
        return new AimStatePacket(buf.readBoolean());
    }

    public static void handle(AimStatePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) GunFireHandler.setAiming(player, msg.aiming);
        });
        ctx.get().setPacketHandled(true);
    }
}
