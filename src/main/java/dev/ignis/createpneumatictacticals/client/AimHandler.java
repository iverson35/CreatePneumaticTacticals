package dev.ignis.createpneumatictacticals.client;

import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import dev.ignis.createpneumatictacticals.gun.GunNbt;
import dev.ignis.createpneumatictacticals.gun.GunStats;
import dev.ignis.createpneumatictacticals.item.GeoGunItem;
import dev.ignis.createpneumatictacticals.client.render.BenchTargetPicker;
import dev.ignis.createpneumatictacticals.module.ModuleDefinition;
import dev.ignis.createpneumatictacticals.module.ModuleManager;
import dev.ignis.createpneumatictacticals.module.ModuleType;
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

    /** seconds for a full hip -> ADS (or ADS -> hip) transition at ergonomics 1 */
    private static final float AIM_TIME_SECONDS = 0.18f;
    /** seconds for a main-sight <-> tactical-sight switch at ergonomics 1 */
    private static final float STANCE_SWITCH_SECONDS = 0.25f;
    /** movement speed factor while fully aiming at ergonomics 1 */
    private static final float AIM_WALK_FACTOR = 0.4f;

    private static float aimProgress = 0f;
    private static float prevAimProgress = 0f;
    private static float stanceBlend = 0f;
    private static float prevStanceBlend = 0f;
    private static long lastStanceGunId = -1;
    private static boolean lastAiming = false;
    private AimHandler() {}

    /** True while holding a gun and holding right mouse (muzzle must be clear). */
    public static boolean isAiming() {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        return player != null && mc.screen == null
                && player.getMainHandItem().getItem() instanceof GeoGunItem
                && mc.options.keyUse.isDown()
                && !MuzzleClearance.isBlocked()
                && !benchFocused(mc);
    }

    /**
     * True while the crosshair picks a 3D workbench marker or the player
     * holds a gun/receiver and looks at a workbench block: right-click goes
     * to the 3D assembly flow instead of aiming.
     */
    private static boolean benchFocused(Minecraft mc) {
        if (BenchTargetPicker.wouldInteract()) return true;
        if (mc.hitResult == null
                || !(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit)) return false;
        if (!(mc.level.getBlockState(hit.getBlockPos()).getBlock()
                instanceof dev.ignis.createpneumatictacticals.block.GunWorkbenchBlock)) return false;
        ItemStack held = mc.player.getMainHandItem();
        if (held.getItem() instanceof GeoGunItem) return true;
        ModuleDefinition def = ModuleManager.definitionOf(held);
        return def != null && def.type == ModuleType.RECEIVER;
    }

    /** Smoothed aim transition progress, render-interpolated. 0 = hip, 1 = fully aimed. */
    public static float aimProgress(float partialTick) {
        return Mth.lerp(partialTick, prevAimProgress, aimProgress);
    }

    /**
     * Sight-stance blend, render-interpolated. 0 = main sight, 1 = tactical
     * (canted) sight. Ramps over {@link #STANCE_SWITCH_SECONDS} (scaled by
     * ergonomics) after a switch so the gun slides between the two sight
     * pictures instead of snapping.
     */
    public static float stanceBlend(float partialTick) {
        return Mth.lerp(partialTick, prevStanceBlend, stanceBlend);
    }


    /**
     * Eased view scale shared by FOV and mouse sensitivity:
     * {@code 1 / (1 + (zoom - 1) * easedProgress)}. 1 while not aiming.
     */
    public static double aimViewScale() {
        // third person: aiming never zooms (F5 cameras keep their FOV)
        if (Minecraft.getInstance().options.getCameraType() != net.minecraft.client.CameraType.FIRST_PERSON)
            return 1.0;
        float p = aimProgress(Minecraft.getInstance().getFrameTime());
        if (p <= 0f) return 1.0;
        p = p * p * (3f - 2f * p);
        return 1.0 / (1.0 + (zoom() - 1.0) * p);
    }

    /** Current zoom factor for the held gun; lerps between the two sight stances. */
    public static double zoom() {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return 1.0;
        GunStats stats = GunStats.ofGun(player.getMainHandItem());
        float b = stanceBlend(mc.getFrameTime());
        b = b * b * (3f - 2f * b);
        return Mth.lerp(b, stats.aimZoom, stats.tacticalAimZoom);
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
        Player p = Minecraft.getInstance().player;
        ItemStack heldGun = p != null ? p.getMainHandItem() : ItemStack.EMPTY;
        double ergo = GunStats.ergoScale(heldGun);
        // ergonomics scales both directions of the hip<->ADS transition
        float step = (float) (1f / (AIM_TIME_SECONDS * 20f) * ergo);
        aimProgress = Mth.clamp(aimProgress + (aiming ? step : -step), 0f, 1f);
        // sight-stance blend: follows the per-gun remembered stance (NBT),
        // ramps over STANCE_SWITCH_SECONDS * ergonomics so X-switching
        // slides the gun.
        // Switching GUNS snaps instead — the two guns' camera bones differ,
        // a cross-gun blend would slide to nowhere.
        prevStanceBlend = stanceBlend;
        boolean tactical = false;
        long gunId = -1;
        if (p != null && p.getMainHandItem().getItem() instanceof GeoGunItem) {
            tactical = "tactical".equals(GunNbt.getAimStance(p.getMainHandItem()));
            gunId = software.bernie.geckolib.animatable.GeoItem.getId(p.getMainHandItem());
        }
        if (gunId != lastStanceGunId) {
            lastStanceGunId = gunId;
            stanceBlend = prevStanceBlend = tactical ? 1f : 0f;
        }
        float stanceStep = (float) (1f / (STANCE_SWITCH_SECONDS * 20f) * ergo);
        stanceBlend = Mth.clamp(stanceBlend + (tactical ? stanceStep : -stanceStep), 0f, 1f);
    }

    @SubscribeEvent
    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        event.setFOV(event.getFOV() * aimViewScale());
    }

    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        float p = aimProgress; // tick-level granularity is fine for movement
        if (p <= 0f) return;
        // ergonomics lets the shooter keep more of their stride while aiming;
        // the penalty share itself is clamped to [0, 0.9] so a stacked build
        // can never fully cancel the slowdown
        ItemStack gun = event.getEntity().getMainHandItem();
        double ergo = GunStats.ergoScale(gun);
        float penalty = (float) Math.min(0.9, (1f - AIM_WALK_FACTOR) / ergo);
        float factor = 1f - penalty * p;
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
        if (mc.player == null) return;
        if (event.getKeyMapping() == mc.options.keyUse) {
            // 3D workbench: route every hand state (gun/receiver/module/empty)
            // through the packet flow; cancel vanilla so block.use and the
            // item-dip animation never fire for consumed workbench clicks.
            // Gate: a marker hovered (any bench in view) OR the crosshair
            // block is the bench itself (staging a gun/receiver).
            boolean benchBlock = mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
                    && mc.level.getBlockState(hit.getBlockPos()).getBlock()
                        instanceof dev.ignis.createpneumatictacticals.block.GunWorkbenchBlock;
            if ((benchBlock || BenchTargetPicker.wouldInteract())
                    && BenchTargetPicker.sendRightClick()) {
                event.setCanceled(true);
                event.setSwingHand(false);
                return;
            }
            if (!(mc.player.getMainHandItem().getItem() instanceof GeoGunItem)) return;
        } else if (!(mc.player.getMainHandItem().getItem() instanceof GeoGunItem)) {
            return;
        }
        // gun holder: swallow use/attack (aiming owns right click, no melee)
        if (event.getKeyMapping() == mc.options.keyUse
                || event.getKeyMapping() == mc.options.keyAttack) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }
}
