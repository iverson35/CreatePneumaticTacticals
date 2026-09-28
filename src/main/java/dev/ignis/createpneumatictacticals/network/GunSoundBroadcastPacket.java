package dev.ignis.createpneumatictacticals.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C: play a gun animation's keyframe sound at another player's position —
 * see {@link GunSoundPacket} for why the keyframe itself is the sync signal.
 * Sent to players within the sound's audible range, the shooter excluded
 * (their own client already played it locally).
 */
public class GunSoundBroadcastPacket {
    public final int shooterId;
    public final String soundId;

    public GunSoundBroadcastPacket(int shooterId, String soundId) {
        this.shooterId = shooterId;
        this.soundId = soundId;
    }

    public static void encode(GunSoundBroadcastPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.shooterId);
        buf.writeUtf(msg.soundId);
    }

    public static GunSoundBroadcastPacket decode(FriendlyByteBuf buf) {
        return new GunSoundBroadcastPacket(buf.readVarInt(), buf.readUtf());
    }

    public static void handle(GunSoundBroadcastPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> () -> dev.ignis.createpneumatictacticals.client.render.GunSoundKeyframes.playRemote(msg)));
        ctx.get().setPacketHandled(true);
    }
}
