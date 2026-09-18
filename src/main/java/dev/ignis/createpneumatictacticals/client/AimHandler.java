package dev.ignis.createpneumatictacticals.client;

import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import dev.ignis.createpneumatictacticals.gun.GunNbt;
import dev.ignis.createpneumatictacticals.gun.GunStats;
import dev.ignis.createpneumatictacticals.item.GeoGunItem;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Aim state (hold right mouse with a gun): smoothed FOV zoom by the installed
 * sight, movement slowed, sprint cancelled.
 *
 * <p>The aim transition is driven by {@code aimProgress} (0..1), advanced per
 * client tick and interpolated with the render partial tick so both the FOV
 * and the movement penalty ease in/out instead of snapping. FOV mapping
 * mirrors pointblank: {@code fov = base / (1 + (zoom - 1) * easedProgress)}.
 *
 * <p>Vanilla click processing for attack/use is cancelled at the input event
 * while holding a gun: {@code Minecraft.startUseItem()} calls
 * {@code ItemInHandRenderer.itemUsed()} on any consumed use result, which is
 * the visible "item dips" animation. Cancelling here also stops chest doors
 * etc. from reacting; {@code GunItem#use} remains as a server-side backstop.
 */
@Mod.EventBusSubscriber(modid = CreatePneumaticTacticals.MODID, value = Dist.CLIENT)
public final class AimHandler {

    /** seconds for a full hip -> ADS (or ADS -> hip) transition */
    private static final float AIM_TIME_SECONDS = 0.18f;
    /** movement speed factor while fully aiming */
    private static final float AIM_WALK_FACTOR = 0.4f;

    private static float aimProgress = 0f;
    private static float prevAimProgress = 0f;
    private static boolean lastAiming = false;
    private AimHandler() {}

    /** True while holding a gun and holding right mouse. */
    public static boolean isAiming() {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        return player != null && mc.screen == null
                && player.getMainHandItem().getItem() instanceof GeoGunItem
                && mc.options.keyUse.isDown();
    }

    /** Smoothed aim transition progress, render-interpolated. 0 = hip, 1 = fully aimed. */
    public static float aimProgress(float partialTick) {
        return Mth.lerp(partialTick, prevAimProgress, aimProgress);
    }


    /**
     * Eased view scale shared by FOV and mouse sensitivity:
     * {@code 1 / (1 + (zoom - 1) * easedProgress)}. 1 while not aiming.
     */
    public static double aimViewScale() {
        float p = aimProgress(Minecraft.getInstance().getFrameTime());
        if (p <= 0f) return 1.0;
        p = p * p * (3f - 2f * p);
        return 1.0 / (1.0 + (zoom() - 1.0) * p);
    }

    /** Current zoom factor for the held gun (aim stance aware). */
    public static double zoom() {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return 1.0;
        ItemStack gun = player.getMainHandItem();
        GunStats stats = GunStats.ofGun(gun);
        return "tactical".equals(GunNbt.getAimStance(gun)) ? stats.tacticalAimZoom : stats.aimZoom;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        boolean aiming = isAiming();
        if (aiming != lastAiming) {
            lastAiming = aiming;
            dev.ignis.createpneumatictacticals.network.CptNetwork.CHANNEL.sendToServer(
                    new dev.ignis.createpneumatictacticals.network.AimStatePacket(aiming));
        }
        prevAimProgress = aimProgress;
        float step = 1f / (AIM_TIME_SECONDS * 20f);
        aimProgress = Mth.clamp(aimProgress + (aiming ? step : -step), 0f, 1f);
    }

    @SubscribeEvent
    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        event.setFOV(event.getFOV() * aimViewScale());
    }

    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        float p = aimProgress; // tick-level granularity is fine for movement
        if (p <= 0f) return;
        float factor = 1f - (1f - AIM_WALK_FACTOR) * p;
        event.getInput().leftImpulse *= factor;
        event.getInput().forwardImpulse *= factor;
        event.getEntity().setSprinting(false);
    }

    /**
     * Swallow vanilla attack/use clicks while holding a gun. Without this,
     * {@code use()} returning CONSUME makes vanilla play the item-dip
     * animation every right click, and left click swings/breaks.
     */
    @SubscribeEvent
    public static void onClickInput(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null
                || !(mc.player.getMainHandItem().getItem() instanceof GeoGunItem)) return;
        if (event.getKeyMapping() == mc.options.keyUse
                || event.getKeyMapping() == mc.options.keyAttack) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }
}
