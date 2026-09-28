package dev.ignis.createpneumatictacticals.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * S2C: someone fired a gun — puff the muzzle smoke on this client too. Sent to
 * every player within {@link #RADIUS} blocks of the shooter, the shooter
 * excluded (their own client already puffed, and it owns the render-pass
 * muzzle anchor the others cannot have).
 *
 * <p>The server ships semantics only: who fired, which ammo, which muzzle
 * device. The receiving client looks the ammo's cadence and the device's gas
 * guides up in its own (identical, see the channel handshake) gunpack, so no
 * per-particle traffic ever leaves the server. {@code gasSuppression} rides
 * along as a float because it is a rolled per-item value — it cannot be
 * resolved from the muzzle id alone.
 *
 * <p>{@code muzzle} / {@code muzzleUp} / {@code dir} come from the shooter's
 * own frame (see {@link FireRequestPacket}): the render-pass muzzle tip in
 * world space, the gun's up axis (gas-guide port roll), and the ballistic
 * direction the shot followed. The muzzle only exists on the shooter's
 * client, so it is the one thing that must travel; when it is absent the
 * receiving client falls back to the eye + look approximation.
 */
public class MuzzleSmokePacket {
    /** broadcast radius around the shooter, in blocks */
    public static final double RADIUS = 48.0;

    public final int shooterId;
    public final String ammoId;
    /** installed muzzle device id, null when the gun has none */
    @Nullable public final String muzzleId;
    public final float gasSuppression;
    /** shooter's render-pass muzzle tip in world space, null = eye fallback */
    @Nullable public final Vec3 muzzle;
    /** gun up axis for the gas-guide port roll, null = world-up fallback */
    @Nullable public final Vec3 muzzleUp;
    /** ballistic direction of the shot (shooter's own frame) */
    public final Vec3 dir;

    public MuzzleSmokePacket(int shooterId, String ammoId, @Nullable String muzzleId, float gasSuppression,
                             @Nullable Vec3 muzzle, @Nullable Vec3 muzzleUp, Vec3 dir) {
        this.shooterId = shooterId;
        this.ammoId = ammoId;
        this.muzzleId = muzzleId;
        this.gasSuppression = gasSuppression;
        this.muzzle = muzzle;
        this.muzzleUp = muzzleUp;
        this.dir = dir;
    }

    public static void encode(MuzzleSmokePacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.shooterId);
        buf.writeUtf(msg.ammoId);
        buf.writeBoolean(msg.muzzleId != null);
        if (msg.muzzleId != null) buf.writeUtf(msg.muzzleId);
        buf.writeFloat(msg.gasSuppression);
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
        buf.writeFloat((float) msg.dir.x);
        buf.writeFloat((float) msg.dir.y);
        buf.writeFloat((float) msg.dir.z);
    }

    public static MuzzleSmokePacket decode(FriendlyByteBuf buf) {
        int shooterId = buf.readVarInt();
        String ammoId = buf.readUtf();
        String muzzleId = buf.readBoolean() ? buf.readUtf() : null;
        float gasSuppression = buf.readFloat();
        Vec3 muzzle = buf.readBoolean() ? new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()) : null;
        Vec3 muzzleUp = buf.readBoolean() ? new Vec3(buf.readFloat(), buf.readFloat(), buf.readFloat()) : null;
        Vec3 dir = new Vec3(buf.readFloat(), buf.readFloat(), buf.readFloat());
        return new MuzzleSmokePacket(shooterId, ammoId, muzzleId, gasSuppression, muzzle, muzzleUp, dir);
    }

    public static void handle(MuzzleSmokePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> () -> dev.ignis.createpneumatictacticals.client.MuzzleSmoke.onRemoteFire(msg)));
        ctx.get().setPacketHandled(true);
    }
}
