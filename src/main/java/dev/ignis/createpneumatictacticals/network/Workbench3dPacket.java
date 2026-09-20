package dev.ignis.createpneumatictacticals.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import dev.ignis.createpneumatictacticals.menu.WorkbenchAssembler;

import java.util.function.Supplier;

/**
 * C2S: 3D gun workbench interactions. The client picked a marker hitbox in
 * world space; the server resolves everything from NBT + held item.
 * Mount points travel as semantic ids ("loc_barrel", or the
 * handguard-position mount bone "loc_handguard_top", ...); positions are
 * purely visual on the client and never trusted here.
 *
 * <ul>
 * <li>STAGE — player right-clicked the bench block holding a gun or a
 * receiver; server stages it (auto-creates the bare gun for receivers).</li>
 * <li>INSTALL — right-clicked a [+] marker holding a module; server
 * validates via GunNbt and bakes the module into the gun NBT.</li>
 * <li>REMOVE — right-clicked a [-] marker with an empty hand; server
 * removes the module from NBT and hands the item back (dye travels).</li>
 * <li>TAKE — right-clicked the [▼] marker with an empty hand; the gun
 * leaves the bench (modules are baked into its NBT).</li>
 * </ul>
 */
public class Workbench3dPacket {

    public enum Action {
        STAGE, INSTALL, REMOVE, TAKE
    }

    public final Action action;
    public final BlockPos pos;
    /** hand used for STAGE/INSTALL (server reads the held item from it) */
    public final boolean offhand;
    /** mount bone id for INSTALL (module to install = held item) / REMOVE (module id) */
    @Nullable public final ResourceLocation mountId;

    public Workbench3dPacket(Action action, BlockPos pos, boolean offhand,
                             @Nullable ResourceLocation mountId) {
        this.action = action;
        this.pos = pos;
        this.offhand = offhand;
        this.mountId = mountId;
    }

    public static void encode(Workbench3dPacket msg, FriendlyByteBuf buf) {
        buf.writeByte(msg.action.ordinal());
        buf.writeBlockPos(msg.pos);
        buf.writeBoolean(msg.offhand);
        boolean has = msg.mountId != null;
        buf.writeBoolean(has);
        if (has) buf.writeResourceLocation(msg.mountId);
    }

    public static Workbench3dPacket decode(FriendlyByteBuf buf) {
        Action[] values = Action.values();
        Action action = values[Math.floorMod(buf.readByte(), values.length)];
        BlockPos pos = buf.readBlockPos();
        boolean offhand = buf.readBoolean();
        ResourceLocation mountId = buf.readBoolean() ? buf.readResourceLocation() : null;
        return new Workbench3dPacket(action, pos, offhand, mountId);
    }

    public static void handle(Workbench3dPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            // distance guard: interactions must be at arm's length
            // client UI range is 4 blocks; a little slack covers movement
            // between the client's check and this handler
            if (player.distanceToSqr(msg.pos.getX() + 0.5, msg.pos.getY() + 0.5,
                    msg.pos.getZ() + 0.5) > 25.0) return;
            switch (msg.action) {
                case STAGE -> WorkbenchAssembler.stageGun(player, msg.pos,
                        WorkbenchAssembler.InteractionHandUse.of(offhand(msg)));
                case INSTALL -> {
                    if (msg.mountId == null) return;
                    WorkbenchAssembler.installModule(player, msg.pos,
                            WorkbenchAssembler.InteractionHandUse.of(offhand(msg)), msg.mountId);
                }
                case REMOVE -> {
                    if (msg.mountId == null) return;
                    WorkbenchAssembler.removeModule(player, msg.pos, msg.mountId);
                }
                case TAKE -> WorkbenchAssembler.takeGun(player, msg.pos);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private static net.minecraft.world.InteractionHand offhand(Workbench3dPacket msg) {
        return msg.offhand ? net.minecraft.world.InteractionHand.OFF_HAND
                : net.minecraft.world.InteractionHand.MAIN_HAND;
    }
}