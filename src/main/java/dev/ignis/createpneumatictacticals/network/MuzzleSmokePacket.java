package dev.ignis.createpneumatictacticals.network;

import net.minecraft.network.FriendlyByteBuf;
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
 */
public class MuzzleSmokePacket {
    /** broadcast radius around the shooter, in blocks */
    public static final double RADIUS = 48.0;

    public final int shooterId;
    public final String ammoId;
    /** installed muzzle device id, null when the gun has none */
    @Nullable public final String muzzleId;
    public final float gasSuppression;

    public MuzzleSmokePacket(int shooterId, String ammoId, @Nullable String muzzleId, float gasSuppression) {
        this.shooterId = shooterId;
        this.ammoId = ammoId;
        this.muzzleId = muzzleId;
        this.gasSuppression = gasSuppression;
    }

    public static void encode(MuzzleSmokePacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.shooterId);
        buf.writeUtf(msg.ammoId);
        buf.writeBoolean(msg.muzzleId != null);
        if (msg.muzzleId != null) buf.writeUtf(msg.muzzleId);
        buf.writeFloat(msg.gasSuppression);
    }

    public static MuzzleSmokePacket decode(FriendlyByteBuf buf) {
        int shooterId = buf.readVarInt();
        String ammoId = buf.readUtf();
        String muzzleId = buf.readBoolean() ? buf.readUtf() : null;
        return new MuzzleSmokePacket(shooterId, ammoId, muzzleId, buf.readFloat());
    }

    public static void handle(MuzzleSmokePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> () -> dev.ignis.createpneumatictacticals.client.MuzzleSmoke.onRemoteFire(msg)));
        ctx.get().setPacketHandled(true);
    }
}
