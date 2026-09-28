package dev.ignis.createpneumatictacticals.client;

import dev.ignis.createpneumatictacticals.client.render.GunAnimTiming;
import dev.ignis.createpneumatictacticals.gun.GunNbt;
import dev.ignis.createpneumatictacticals.item.GeoGunItem;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import net.minecraftforge.api.distmarker.Dist;

/**
 * Aim-animation edge detector: turns the continuous aim state (held right
 * mouse + {@link AimHandler#aimProgress} + the per-gun AimStance NBT) into
 * the discrete aim animation events on the "aim" controller:
 * <ul>
 * <li>rising edge of {@code isAiming()} → {@code aim_start} /
 *     {@code tactical_aim_start}, then the matching hold loop
 *     ({@code aim} / {@code tactical_aim}) once the start animation has
 *     had its length (a gun with only the loop defined starts it right
 *     away);</li>
 * <li>falling edge → {@code aim_end} / {@code tactical_aim_end}, which also
 *     supersedes a still-running hold loop on the same controller;</li>
 * <li>stance flip while aiming → end of the old stance + start of the new
 *     one, each with its own loop;</li>
 * <li>gun switched away → hard {@link GunAnimationDriver#interrupt} (the
 *     pose snaps back to rest there); aiming the new gun is a fresh edge.</li>
 * </ul>
 *
 * <p>Progress (how far the FOV eased in) deliberately does NOT gate the
 * edges: {@code isAiming()} is the player's intent and the animation is the
 * visual response, the same tick. The hold loop's scheduled start also uses
 * the start animation's true length when the file provides it, so a 30-tick
 * raise keeps the loop waiting 30 ticks instead of the assumed 6.
 *
 * <p>Missing animations are silent: the broadcast filters per part
 * (filterExisting), so a gun may define only {@code aim}, only
 * {@code aim_start}, both, or neither.
 */
@Mod.EventBusSubscriber(modid = CreatePneumaticTacticals.MODID, value = Dist.CLIENT)
public final class AimAnimationState {

    private static boolean wasAiming = false;
    private static boolean wasTactical = false;
    /** gun id the edges were last fired for: a slot switch mid-aim lands on
     *  the same isAiming/stance values, but the new gun never saw an edge */
    private static long lastGunId = -1;
    /** hold-loop handoff latched on an aim-start edge, consumed when it fires */
    private static boolean loopPending = false;
    private static boolean loopTactical = false;
    /** wall-clock ms when the pending hold loop takes over the controller */
    private static long loopAtMs = 0;
    /** assumed aim_start length when the animation file omits it: 0.3s raise */
    private static final double FALLBACK_AIM_START_TICKS = 6;

    private AimAnimationState() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        ItemStack gun = mc.player.getMainHandItem();
        if (!(gun.getItem() instanceof GeoGunItem)) {
            // gun left the hand entirely (not a slot switch to another gun —
            // that still holds A gun): void any pending loop, the pose
            // already reset with the gun
            loopPending = false;
            wasAiming = false;
            return;
        }
        boolean aiming = AimHandler.isAiming();
        boolean tactical = "tactical".equals(GunNbt.getAimStance(gun));
        long now = System.currentTimeMillis();
        long gunId = software.bernie.geckolib.animatable.GeoItem.getId(gun);
        boolean gunSwitched = gunId != lastGunId;
        lastGunId = gunId;

        if (gunSwitched && wasAiming && !aiming) {
            // slot switch mid-aim landed on a NON-gun item: nothing to end on
            // (the pose reset with the gun); just void the pending loop
            loopPending = false;
        } else if (aiming && (!wasAiming || gunSwitched)) {
            // fresh aim edge, OR a slot switch to ANOTHER gun while holding
            // right mouse: the new gun never saw its start edge
            startAim(gun, tactical, now);
        } else if (!aiming && wasAiming) {
            stopAim(wasTactical);
        } else if (aiming && tactical != wasTactical) {
            // stance flip mid-aim (X key): the old stance ends, the new one
            // starts fresh — same edge pair, one tick
            stopAim(wasTactical);
            startAim(gun, tactical, now);
        }
        // the hold loop handoff: after the start edge's animation length
        // (or immediately, when the receiver authored no aim_start)
        if (loopPending && now >= loopAtMs) {
            loopPending = false;
            GunAnimationDriver.onAimLoop(loopTactical);
        }
        wasAiming = aiming;
        wasTactical = tactical;
    }

    private static void startAim(ItemStack gun, boolean tactical, long now) {
        GunAnimationDriver.onAimEdge(tactical ? "tactical_aim_start" : "aim_start");
        boolean hasStart = GunAnimTiming.hasNamedAnimation(gun, tactical ? "tactical_aim_start" : "aim_start");
        double startTicks = hasStart
                ? GunAnimTiming.animLengthTicks(gun, tactical ? "tactical_aim_start" : "aim_start",
                        FALLBACK_AIM_START_TICKS)
                : 0;
        loopPending = true;
        loopTactical = tactical;
        loopAtMs = now + (long) (startTicks * 50.0);
    }

    private static void stopAim(boolean wasTactical) {
        loopPending = false; // a start cut short never reaches its loop
        GunAnimationDriver.onAimEdge(wasTactical ? "tactical_aim_end" : "aim_end");
    }
}