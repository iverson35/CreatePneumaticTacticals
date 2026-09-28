package dev.ignis.createpneumatictacticals.network;

import dev.ignis.createpneumatictacticals.gunpack.GunpackSounds;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * C2S: the local player's gun animation just reached a sound keyframe (json
 * {@code sound_effects}) — relay it to nearby players.
 *
 * <p>The keyframe is the sync signal on purpose: it fires only when the
 * animation actually got this far, so interrupting an animation (firing
 * mid-reload, switching off the gun) stops the sound on every client at once
 * instead of leaving a remote copy playing. No animation state is synced, so
 * there is nothing to un-trigger either.
 *
 * <p>The client ships the sound id it resolved; the server checks it against
 * the gunpack's own sound list — a hacked client cannot make the server play
 * arbitrary sounds — and relays {@link GunSoundBroadcastPacket} to players
 * inside the sound's own range (the pack's {@code attenuation_distance}).
 */
public class GunSoundPacket {
    /** at most one relay per player per this many ticks */
    private static final int MIN_TICK_GAP = 2;
    private static final Map<Player, Integer> LAST_RELAY = new WeakHashMap<>();

    public final String soundId;

    public GunSoundPacket(String soundId) {
        this.soundId = soundId;
    }

    public static void encode(GunSoundPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.soundId);
    }

    public static GunSoundPacket decode(FriendlyByteBuf buf) {
        return new GunSoundPacket(buf.readUtf());
    }

    public static void handle(GunSoundPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender == null) return;
            ResourceLocation id = ResourceLocation.tryParse(msg.soundId);
            if (id == null || !GunpackSounds.isGunpackSound(id)) return;
            // stamp before the gap check: a spamming client keeps pushing its
            // own window forward instead of sliding under it
            Integer last = LAST_RELAY.put(sender, sender.tickCount);
            if (last != null && sender.tickCount - last < MIN_TICK_GAP) return;
            if (!(sender.level() instanceof ServerLevel level)) return;
            SoundEvent sound = net.minecraftforge.registries.ForgeRegistries.SOUND_EVENTS.getValue(id);
            if (sound == null) return;
            double radius = sound.getRange(1.0f);
            GunSoundBroadcastPacket out = new GunSoundBroadcastPacket(sender.getId(), msg.soundId);
            for (ServerPlayer other : level.players()) {
                if (other == sender) continue;
                if (other.distanceToSqr(sender) > radius * radius) continue;
                CptNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> other), out);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
