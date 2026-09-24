package dev.ignis.createpneumatictacticals.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S: request a state switch on the held gun. Server validates and writes
 * gun NBT, then confirms implicitly via NBT sync.
 */
public class GunActionPacket {

    public enum Action {
        NEXT_FIRE_MODE,
        CYCLE_AIM_STANCE,
        /** aborted reload: drop the ammo swap it was going to apply */
        CANCEL_AMMO_SWAP
    }

    public final Action action;

    public GunActionPacket(Action action) {
        this.action = action;
    }

    public static void encode(GunActionPacket msg, FriendlyByteBuf buf) {
        buf.writeByte(msg.action.ordinal());
    }

    public static GunActionPacket decode(FriendlyByteBuf buf) {
        Action[] values = Action.values();
        return new GunActionPacket(values[Math.floorMod(buf.readByte(), values.length)]);
    }

    public static void handle(GunActionPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) {
                try {
                    GunActionHandler.onAction(player, msg.action);
                } catch (Exception e) {
                    // enqueueWork futures swallow exceptions silently — log
                    // or a failed action just looks like "nothing happens"
                    com.mojang.logging.LogUtils.getLogger().error(
                            "gun action {} failed for {}", msg.action, player.getName().getString(), e);
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}