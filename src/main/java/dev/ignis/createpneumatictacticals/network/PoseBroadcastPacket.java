package dev.ignis.createpneumatictacticals.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C: broadcast pose changes of a player to tracking clients for third-person
 * rendering. Pose string values:
 *   hip | ads | tactical | low_ready | high_ready | reloading | reloading_empty
 */
public class PoseBroadcastPacket {

    public final int entityId;
    public final Pose pose;

    public enum Pose {
        HIP, ADS, TACTICAL, LOW_READY, HIGH_READY, RELOADING,
        /** empty-magazine reload: same choreography + a bolt-rack inward tap */
        RELOADING_EMPTY
    }

    public PoseBroadcastPacket(int entityId, Pose pose) {
        this.entityId = entityId;
        this.pose = pose;
    }

    public static void encode(PoseBroadcastPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.entityId);
        buf.writeByte(msg.pose.ordinal());
    }

    public static PoseBroadcastPacket decode(FriendlyByteBuf buf) {
        Pose[] values = Pose.values();
        return new PoseBroadcastPacket(buf.readVarInt(), values[Math.floorMod(buf.readByte(), values.length)]);
    }

    public static void handle(PoseBroadcastPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // ClientState store; consumed by third-person renderer
            ClientPoses.set(msg.entityId, msg.pose);
        });
        ctx.get().setPacketHandled(true);
    }
}