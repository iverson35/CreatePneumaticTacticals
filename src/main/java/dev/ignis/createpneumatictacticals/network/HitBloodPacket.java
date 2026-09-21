package dev.ignis.createpneumatictacticals.network;

import dev.ignis.createpneumatictacticals.client.HitBlood;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C: a gun round landed on a living entity - spray blood at the impact.
 * Sent to every player within {@link #RADIUS} blocks of the hit point.
 *
 * <p>The server ships semantics only (where, which way, how many drops); the
 * cone and the speeds are rolled client side by {@code HitBlood}, so no
 * per-particle traffic ever leaves the server.
 */
public class HitBloodPacket {
    /** broadcast radius around the impact point, in blocks */
    public static final double RADIUS = 24.0;

    private final Vec3 pos;
    private final Vec3 dir;
    private final int count;

    public HitBloodPacket(Vec3 pos, Vec3 dir, int count) {
        this.pos = pos;
        this.dir = dir;
        this.count = count;
    }

    public static void encode(HitBloodPacket msg, FriendlyByteBuf buf) {
        buf.writeDouble(msg.pos.x);
        buf.writeDouble(msg.pos.y);
        buf.writeDouble(msg.pos.z);
        buf.writeDouble(msg.dir.x);
        buf.writeDouble(msg.dir.y);
        buf.writeDouble(msg.dir.z);
        buf.writeVarInt(msg.count);
    }

    public static HitBloodPacket decode(FriendlyByteBuf buf) {
        return new HitBloodPacket(
                new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()),
                new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()),
                buf.readVarInt());
    }

    public static void handle(HitBloodPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> () -> HitBlood.spawn(msg.pos, msg.dir, msg.count)));
        ctx.get().setPacketHandled(true);
    }
}
