package dev.ignis.createpneumatictacticals.network;

import dev.ignis.createpneumatictacticals.client.GunHudOverlay;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** S2C: a gun projectile fired by this player hit a living entity (hitmarker). */
public class HitConfirmPacket {

    public static void encode(HitConfirmPacket msg, FriendlyByteBuf buf) {}

    public static HitConfirmPacket decode(FriendlyByteBuf buf) {
        return new HitConfirmPacket();
    }

    public static void handle(HitConfirmPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> GunHudOverlay::showHitmarker));
        ctx.get().setPacketHandled(true);
    }
}
