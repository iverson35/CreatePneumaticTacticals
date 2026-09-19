package dev.ignis.createpneumatictacticals.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S: player requests a shot. The client piggybacks the exact eye position
 * and look direction of the frame the click happened in: the server's own
 * player rotation lags the client camera by up to ~50ms (rotation syncs at
 * 20Hz), which put every turned shot on the wrong side of the crosshair.
 * The server still validates everything else (ammo/air/fire rate) and
 * clamps the trusted direction to the server's own look vector.
 */
public class FireRequestPacket {

    public final double eyeX, eyeY, eyeZ;
    public final double dirX, dirY, dirZ;

    public FireRequestPacket(Vec3 eye, Vec3 dir) {
        this.eyeX = eye.x; this.eyeY = eye.y; this.eyeZ = eye.z;
        this.dirX = dir.x; this.dirY = dir.y; this.dirZ = dir.z;
    }

    private FireRequestPacket(double eyeX, double eyeY, double eyeZ,
                              double dirX, double dirY, double dirZ) {
        this.eyeX = eyeX; this.eyeY = eyeY; this.eyeZ = eyeZ;
        this.dirX = dirX; this.dirY = dirY; this.dirZ = dirZ;
    }

    public static void encode(FireRequestPacket msg, FriendlyByteBuf buf) {
        buf.writeDouble(msg.eyeX);
        buf.writeDouble(msg.eyeY);
        buf.writeDouble(msg.eyeZ);
        buf.writeDouble(msg.dirX);
        buf.writeDouble(msg.dirY);
        buf.writeDouble(msg.dirZ);
    }

    public static FireRequestPacket decode(FriendlyByteBuf buf) {
        return new FireRequestPacket(buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readDouble(), buf.readDouble(), buf.readDouble());
    }

    public static void handle(FireRequestPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) {
                GunFireHandler.onFireRequest(player,
                        new Vec3(msg.eyeX, msg.eyeY, msg.eyeZ),
                        new Vec3(msg.dirX, msg.dirY, msg.dirZ));
            }
        });
        ctx.get().setPacketHandled(true);
    }
}