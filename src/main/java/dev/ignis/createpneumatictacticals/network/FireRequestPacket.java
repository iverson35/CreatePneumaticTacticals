package dev.ignis.createpneumatictacticals.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * C2S: player requests a shot. The client piggybacks the exact eye position
 * and look direction of the frame the click happened in: the server's own
 * player rotation lags the client camera by up to ~50ms (rotation syncs at
 * 20Hz), which put every turned shot on the wrong side of the crosshair.
 * The server still validates everything else (ammo/air/fire rate) and
 * clamps the trusted direction to the server's own look vector.
 *
 * <p>{@code aim} is the same frame's ADS transition progress (0 = hip,
 * 1 = sights up): the spread cone closes on the client's own curve, so a
 * shot fired mid-raise lands between hipfire and aimed instead of snapping
 * to pinpoint the instant the aim key went down.
 *
 * <p>{@code muzzle} carries the render-pass muzzle frame (world-space tip +
 * gun up axis): only the shooter's client ever samples the animated bone, so
 * remote clients would otherwise have to guess the barrel from the eye. The
 * server validates it (near the eye, finite, unit-ish up) and forwards it in
 * {@link MuzzleSmokePacket}; a missing or rejected frame makes receivers fall
 * back to the eye approximation.
 */
public class FireRequestPacket {

    public final double eyeX, eyeY, eyeZ;
    public final double dirX, dirY, dirZ;
    public final float aim;
    /** world-space muzzle tip, null when the shooter had no render-pass sample */
    @Nullable public final Vec3 muzzle;
    /** gun up axis (carries the gun's roll for gas-guide ports), null likewise */
    @Nullable public final Vec3 muzzleUp;

    public FireRequestPacket(Vec3 eye, Vec3 dir, float aim, @Nullable Vec3 muzzle, @Nullable Vec3 muzzleUp) {
        this.eyeX = eye.x; this.eyeY = eye.y; this.eyeZ = eye.z;
        this.dirX = dir.x; this.dirY = dir.y; this.dirZ = dir.z;
        this.aim = aim;
        this.muzzle = muzzle;
        this.muzzleUp = muzzleUp;
    }

    private FireRequestPacket(double eyeX, double eyeY, double eyeZ,
                              double dirX, double dirY, double dirZ, float aim,
                              @Nullable Vec3 muzzle, @Nullable Vec3 muzzleUp) {
        this.eyeX = eyeX; this.eyeY = eyeY; this.eyeZ = eyeZ;
        this.dirX = dirX; this.dirY = dirY; this.dirZ = dirZ;
        this.aim = aim;
        this.muzzle = muzzle;
        this.muzzleUp = muzzleUp;
    }

    public static void encode(FireRequestPacket msg, FriendlyByteBuf buf) {
        buf.writeDouble(msg.eyeX);
        buf.writeDouble(msg.eyeY);
        buf.writeDouble(msg.eyeZ);
        buf.writeDouble(msg.dirX);
        buf.writeDouble(msg.dirY);
        buf.writeDouble(msg.dirZ);
        buf.writeFloat(msg.aim);
        buf.writeBoolean(msg.muzzle != null);
        if (msg.muzzle != null) {
            buf.writeDouble(msg.muzzle.x);
            buf.writeDouble(msg.muzzle.y);
            buf.writeDouble(msg.muzzle.z);
        }
        buf.writeBoolean(msg.muzzleUp != null);
        if (msg.muzzleUp != null) {
            buf.writeFloat((float) msg.muzzleUp.x);
            buf.writeFloat((float) msg.muzzleUp.y);
            buf.writeFloat((float) msg.muzzleUp.z);
        }
    }

    public static FireRequestPacket decode(FriendlyByteBuf buf) {
        double eyeX = buf.readDouble(), eyeY = buf.readDouble(), eyeZ = buf.readDouble();
        double dirX = buf.readDouble(), dirY = buf.readDouble(), dirZ = buf.readDouble();
        float aim = buf.readFloat();
        Vec3 muzzle = buf.readBoolean() ? new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()) : null;
        Vec3 muzzleUp = buf.readBoolean() ? new Vec3(buf.readFloat(), buf.readFloat(), buf.readFloat()) : null;
        return new FireRequestPacket(eyeX, eyeY, eyeZ, dirX, dirY, dirZ, aim, muzzle, muzzleUp);
    }

    public static void handle(FireRequestPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) {
                GunFireHandler.onFireRequest(player,
                        new Vec3(msg.eyeX, msg.eyeY, msg.eyeZ),
                        new Vec3(msg.dirX, msg.dirY, msg.dirZ),
                        msg.aim, msg.muzzle, msg.muzzleUp);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}