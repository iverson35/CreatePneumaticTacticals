package dev.ignis.createpneumatictacticals.network;

import dev.ignis.createpneumatictacticals.item.GeoGunItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/**
 * C2S: the owning client reports its current gun pose (hip / ads / tactical /
 * low_ready / high_ready / reloading / reloading_empty). Pose inputs are client-local (aim key,
 * reload state machine, ready-pose style config), so the client is the only
 * source of truth; the server validates that a gun is actually held and
 * rebroadcasts to tracking clients for third-person rendering.
 */
public class PoseUpdatePacket {

    public final PoseBroadcastPacket.Pose pose;

    public PoseUpdatePacket(PoseBroadcastPacket.Pose pose) {
        this.pose = pose;
    }

    public static void encode(PoseUpdatePacket msg, FriendlyByteBuf buf) {
        buf.writeByte(msg.pose.ordinal());
    }

    public static PoseUpdatePacket decode(FriendlyByteBuf buf) {
        PoseBroadcastPacket.Pose[] values = PoseBroadcastPacket.Pose.values();
        return new PoseUpdatePacket(values[Math.floorMod(buf.readByte(), values.length)]);
    }

    public static void handle(PoseUpdatePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            // no gun in hand -> force HIP so a stale pose never sticks
            PoseBroadcastPacket.Pose pose = player.getMainHandItem().getItem() instanceof GeoGunItem
                    ? msg.pose : PoseBroadcastPacket.Pose.HIP;
            CptNetwork.CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> player),
                    new PoseBroadcastPacket(player.getId(), pose));
        });
        ctx.get().setPacketHandled(true);
    }
}
