package dev.ignis.createpneumatictacticals.network;
import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class CptNetwork {
    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(CreatePneumaticTacticals.MODID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    private CptNetwork() {
    }

    public static void init() {
        int id = 0;
        // C2S
        CHANNEL.registerMessage(id++, FireRequestPacket.class, FireRequestPacket::encode, FireRequestPacket::decode, FireRequestPacket::handle);
        CHANNEL.registerMessage(id++, ReloadResultPacket.class, ReloadResultPacket::encode, ReloadResultPacket::decode, ReloadResultPacket::handle);
        CHANNEL.registerMessage(id++, GunActionPacket.class, GunActionPacket::encode, GunActionPacket::decode, GunActionPacket::handle);
        CHANNEL.registerMessage(id++, WorkbenchActionPacket.class, WorkbenchActionPacket::encode, WorkbenchActionPacket::decode, WorkbenchActionPacket::handle);
        CHANNEL.registerMessage(id++, AimStatePacket.class, AimStatePacket::encode, AimStatePacket::decode, AimStatePacket::handle);
        // S2C
        CHANNEL.registerMessage(id++, PoseBroadcastPacket.class, PoseBroadcastPacket::encode, PoseBroadcastPacket::decode, PoseBroadcastPacket::handle);
        CHANNEL.registerMessage(id++, HitConfirmPacket.class, HitConfirmPacket::encode, HitConfirmPacket::decode, HitConfirmPacket::handle);
    }
}