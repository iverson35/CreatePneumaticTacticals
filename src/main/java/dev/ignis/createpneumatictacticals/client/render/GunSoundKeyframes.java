package dev.ignis.createpneumatictacticals.client.render;

import com.mojang.logging.LogUtils;
import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import software.bernie.geckolib.core.keyframe.event.SoundKeyframeEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * Sound-keyframe playback for gun and module animations: the
 * {@code sound_effects} entries of the animation json. GeckoLib only
 * dispatches keyframes to a handler registered on the controller — without
 * one it prints "no keyframe handler registered" and stays silent — so the
 * handler is attached to the "anim" (trigger) controllers of the receiver and
 * of every module.
 *
 * <p>Trigger animations are client-driven and only run on the LOCAL player's
 * held gun (GunAnimationDriver), so the keyframe sound is played at the local
 * player. Loudness and pitch are not part of the keyframe: they come from the
 * sound event's entry in the gunpack {@code sounds.json} (vanilla multiplies
 * the entry's volume/pitch into the play call), so authors tune them there —
 * and an edit there applies on F3+T, no restart.
 *
 * <p>A keyframe "effect" resolves as a sound event either by full id
 * ({@code mypack:gun.reload}) or by a bare key ({@code gun.reload}) which is
 * read in the mod's namespace — the default pack's namespace. Unknown ids warn
 * once instead of failing silently.
 */
public final class GunSoundKeyframes {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** one warning per bad id, not per keyframe hit */
    private static final Set<String> WARNED = new HashSet<>();

    private GunSoundKeyframes() {}

    /** {@code AnimationController#setSoundKeyframeHandler} target. */
    public static void play(SoundKeyframeEvent<?> event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        String raw = event.getKeyframeData().getSound();
        SoundEvent sound = resolve(raw);
        if (sound == null) {
            if (WARNED.add(raw)) {
                LOGGER.warn("Animation sound keyframe '{}' is not a registered sound event", raw);
            }
            return;
        }
        // volume/pitch 1.0: the sounds.json entry's own values still apply
        mc.level.playLocalSound(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ(),
                sound, SoundSource.PLAYERS, 1.0f, 1.0f, false);
    }

    /** full id first, then a bare key in the mod's namespace */
    @Nullable
    private static SoundEvent resolve(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        ResourceLocation id = ResourceLocation.tryParse(raw);
        if (id != null) {
            SoundEvent direct = ForgeRegistries.SOUND_EVENTS.getValue(id);
            if (direct != null) return direct;
        }
        if (raw.indexOf(':') < 0) {
            return ForgeRegistries.SOUND_EVENTS.getValue(
                    new ResourceLocation(CreatePneumaticTacticals.MODID, raw));
        }
        return null;
    }
}
